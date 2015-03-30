package controllers

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import org.scalatest.{Matchers, WordSpecLike}
import play.api.libs.iteratee.Enumerator
import play.api.test.FakeRequest

import scala.concurrent.Await
import scala.concurrent.duration._

class ApplicationSpec  extends WordSpecLike with Matchers {
  "MacBodyParser" should {
    "produce a unit when the body digest matches the signature" in {
      val mac = Mac.getInstance(algorithm)
      mac.init(secret)
      val signature = mac.doFinal(body.getBytes("UTF-8"))

      val request = FakeRequest("GET", "/").withHeaders(hmacHeader -> bytes2hex(signature))
      val bodyParser = Application.MacBodyParser(hmacHeader, secret, algorithm)

      val result = Enumerator(body.getBytes("UTF-8")) |>>> bodyParser(request)
      Await.result(result, 5.seconds) shouldBe Right(())
    }

    "produce an unauthorized result when the signature in the header has an invalid value" in {
      val signature = Array[Byte](0, 1, 2)

      val request = FakeRequest("GET", "/").withHeaders(hmacHeader -> bytes2hex(signature))
      val bodyParser = Application.MacBodyParser(hmacHeader, secret, algorithm)

      val result = Enumerator(body.getBytes("UTF-8")) |>>> bodyParser(request)
      Await.result(result, 5.seconds).isLeft shouldBe true
    }
  }

  val algorithm = "HmacSHA1"
  val secret = new SecretKeySpec("somesecret".getBytes, algorithm)

  val hmacHeader = "X-Hub-Signature"

  val body = "some body"

  private def bytes2hex(bytes: Array[Byte]): String =
    bytes.map("%02x".format(_)).mkString
}
