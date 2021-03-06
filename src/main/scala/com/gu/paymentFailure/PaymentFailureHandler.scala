package com.gu.paymentFailure

import com.amazonaws.services.lambda.runtime.Context
import com.gu.util.ApiGatewayResponse._
import com.gu.util.Auth._
import com.gu.util.{ Config, Logging, ZuoraRestService, ZuoraService }
import com.gu.util.ZuoraModels._
import java.io._
import java.text.DecimalFormat
import org.joda.time.LocalDate
import play.api.libs.json.{ JsError, JsSuccess, JsValue, Json }
import scala.math.BigDecimal.decimal
import scala.util.{ Failure, Success, Try }
import scalaz.{ -\/, \/, \/- }

trait PaymentFailureLambda extends Logging {
  def stage: String
  def configAttempt: Try[Config]
  def getZuoraRestService: Try[ZuoraService]
  def queueClient: QueueClient

  def loggableData(callout: PaymentFailureCallout) = {
    s"accountId: ${callout.accountId}, paymentId: ${callout.paymentId}, failureNumber: ${callout.failureNumber}, paymentMethodType: ${callout.paymentMethodType}, currency: ${callout.currency}"
  }

  def handleRequest(inputStream: InputStream, outputStream: OutputStream, context: Context): Unit = {
    logger.info(s"Payment Failure Lambda is starting up in $stage")
    val inputEvent = Json.parse(inputStream)
    configAttempt match {
      case Success(config) => {
        getZuoraRestService.foreach { zuoraRestService =>
          processCallout(inputEvent, outputStream, config)(zuoraRestService)
        }
      }
      case Failure(_) => {
        outputForAPIGateway(outputStream, internalServerError(s"Failed to execute lambda - unable to load configuration from S3"))
      }
    }
  }

  def processCallout(inputEvent: JsValue, outputStream: OutputStream, config: Config)(implicit zuoraService: ZuoraService): Unit = {
    if (deprecatedCredentialsAreValid(inputEvent, config.trustedApiConfig)) {
      logger.info(s"Authenticated request successfully in $stage")
      val maybeBody = (inputEvent \ "body").toOption
      maybeBody.map { body =>
        Json.fromJson[PaymentFailureCallout](Json.parse(body.as[String])) match {
          case callout: JsSuccess[PaymentFailureCallout] =>
            if (validTenant(config.trustedApiConfig, callout.value)) {
              logger.info(s"received ${loggableData(callout.value)}")
              enqueueEmail(callout.value) match {
                case -\/(error) => outputForAPIGateway(outputStream, internalServerError(error))
                case \/-(_) => outputForAPIGateway(outputStream, successfulExecution)
              }
            } else {
              logger.info(s"Incorrect Tenant Id was provided")
              outputForAPIGateway(outputStream, unauthorized)
            }
          case e: JsError =>
            logger.error(s"error parsing callout body: $e")
            outputForAPIGateway(outputStream, badRequest)
        }
      }.getOrElse(
        outputForAPIGateway(outputStream, badRequest)
      )
    } else {
      logger.info("Request from Zuora could not be authenticated")
      outputForAPIGateway(outputStream, unauthorized)
    }
  }

  def enqueueEmail(paymentFailureCallout: PaymentFailureCallout)(implicit zuoraService: ZuoraService): \/[String, Unit] = {
    val accountId = paymentFailureCallout.accountId
    val queueSendResponse = dataCollection(accountId).map { paymentInformation =>
      val message = toMessage(paymentFailureCallout, paymentInformation)
      queueClient.sendDataExtensionToQueue(message) match {
        case Success(messageResult) =>
          logger.info(s"Message queued successfully for account: $accountId")
          \/-(())
        case Failure(error) =>
          logger.error(s"Could not enqueue message for account: $accountId", error)
          -\/(s"Could not enqueue message for account $accountId")
      }
    }
    queueSendResponse.getOrElse(-\/(s"Could not retrieve additional data for account $accountId"))
  }

  def toMessage(paymentFailureCallout: PaymentFailureCallout, paymentFailureInformation: PaymentFailureInformation) = Message(
    DataExtensionName = dataExtensionNameForAttempt(paymentFailureCallout.failureNumber),
    To = ToDef(
      Address = paymentFailureCallout.email,
      SubscriberKey = paymentFailureCallout.email,
      ContactAttributes = ContactAttributesDef(
        SubscriberAttributes = SubscriberAttributesDef(
          SubscriberKey = paymentFailureCallout.email,
          EmailAddress = paymentFailureCallout.email,
          subscriber_id = paymentFailureInformation.subscriptionName,
          product = paymentFailureInformation.product,
          payment_method = paymentFailureCallout.paymentMethodType,
          card_type = paymentFailureCallout.creditCardType,
          card_expiry_date = paymentFailureCallout.creditCardExpirationMonth + "/" + paymentFailureCallout.creditCardExpirationYear,
          first_name = paymentFailureCallout.firstName,
          last_name = paymentFailureCallout.lastName,
          paymentId = paymentFailureCallout.paymentId,
          price = price(paymentFailureInformation.amount, paymentFailureCallout.currency),
          serviceStartDate = serviceDateFormat(paymentFailureInformation.serviceStartDate),
          serviceEndDate = serviceDateFormat(paymentFailureInformation.serviceEndDate)
        )
      )
    )
  )

  val currencySymbol = Map("GBP" -> "£", "AUD" -> "$", "EUR" -> "€", "USD" -> "$", "CAD" -> "$", "NZD" -> "$")

  val decimalFormat = new DecimalFormat("###,###.00")
  def price(amount: Double, currency: String): String = {
    val formattedAmount: String = decimalFormat.format(decimal(amount))
    val upperCaseCurrency = currency.toUpperCase
    val symbol: String = currencySymbol.get(upperCaseCurrency).getOrElse(upperCaseCurrency)
    symbol + formattedAmount
  }

  def serviceDateFormat(d: LocalDate) = d.toString("dd MMMM yyyy")

  //todo see if we need to parse the payment number as an int
  def dataExtensionNameForAttempt: Map[String, String] = Map("1" -> "first-failed-payment-email", "2" -> "second-failed-payment-email", "3" -> "third-failed-payment-email")

  case class PaymentFailureInformation(subscriptionName: String, product: String, amount: Double, serviceStartDate: LocalDate, serviceEndDate: LocalDate)

  //todo for now just return an option here but the error handling has to be refactored a little bit
  def dataCollection(accountId: String)(implicit zuoraService: ZuoraService): Option[PaymentFailureInformation] = {
    logger.info(s"Attempting to get further details from account $accountId")
    val unpaidInvoices = zuoraService.getInvoiceTransactions(accountId).map {
      invoiceTransactionSummary =>
        invoiceTransactionSummary.invoices.filter {
          invoice => unpaid(invoice)
        }
    }
    unpaidInvoices.toOption.flatMap { unpaid =>
      unpaid.headOption match {
        case Some(invoice) => {
          logger.info(s"Found the following unpaid invoice: $invoice")
          val positiveInvoiceItems = invoice.invoiceItems.filter(item => invoiceItemFilter(item))
          positiveInvoiceItems.headOption.map {
            item =>
              {
                val paymentFailureInfo = PaymentFailureInformation(item.subscriptionName, item.productName, invoice.amount, item.serviceStartDate, item.serviceEndDate)
                logger.info(s"Payment failure information for account: $accountId is: $paymentFailureInfo")
                paymentFailureInfo
              }
          }
        }
        case None => {
          logger.error(s"No unpaid invoice found - nothing to do")
          None
        }
      }
    }
  }

  def unpaid(itemisedInvoice: ItemisedInvoice): Boolean = {
    itemisedInvoice.balance > 0 && itemisedInvoice.status == "Posted"
  }

  def invoiceItemFilter(item: InvoiceItem): Boolean = {
    item.chargeAmount > 0 // remove discounts, holiday credits and free products
  }

}

object Lambda extends PaymentFailureLambda {
  override val stage = System.getenv("Stage")
  override val configAttempt = Config.load(stage)
  override val getZuoraRestService = configAttempt.map {
    config => new ZuoraRestService(config.zuoraRestConfig)
  }
  override val queueClient = SqsClient
}

