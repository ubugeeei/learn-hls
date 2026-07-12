//> using target.scope test
package hls.crypto

import hls.model.ValueTypes.*
import java.nio.charset.StandardCharsets

final class Aes128SegmentCipherSuite extends munit.FunSuite:
  test("derive the implicit IV from media sequence in network byte order"):
    val iv       = InitializationVector.fromMediaSequence(MediaSequence.unsafe(0x0102030405060708L))
    val expected = InitializationVector.parse("0x00000000000000000102030405060708").toOption.get
    assertEquals(iv, expected)

  test("encrypt and decrypt a whole segment with PKCS#7 padding"):
    val key       = Aes128Key.from((0 until 16).map(_.toByte).toArray).toOption.get
    val iv        = InitializationVector.parse("0x101112131415161718191a1b1c1d1e1f").toOption.get
    val plaintext = "a segment whose size is not a block multiple".getBytes(StandardCharsets.UTF_8)
    val encrypted = Aes128SegmentCipher.encrypt(plaintext, key, iv).toOption.get
    assert(encrypted.length % 16 == 0)
    assert(!encrypted.sameElements(plaintext))
    assertEquals(
      Aes128SegmentCipher.decrypt(encrypted, key, iv).toOption.get.toVector,
      plaintext.toVector
    )

  test("key and IV validation failures are declarative and redact secrets"):
    assertEquals(Aes128Key.from(Array.fill[Byte](15)(0)), Left(CryptoError.InvalidKeyLength(15)))
    assert(
      InitializationVector
        .parse("0x1234")
        .left
        .toOption
        .exists(_.isInstanceOf[CryptoError.InvalidIv])
    )
    val key = Aes128Key.from(Array.fill[Byte](16)(7)).toOption.get
    assertEquals(key.toString, "Aes128Key(<redacted>)")
