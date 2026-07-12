package hls.crypto

import hls.model.ValueTypes.*
import javax.crypto.Cipher
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
import scala.util.Try

/** AES-128 key material with exact-length validation and redacted rendering. */
final class Aes128Key private (private[crypto] val bytes: Array[Byte]):
  override def equals(other: Any): Boolean = other match
    case value: Aes128Key => java.security.MessageDigest.isEqual(bytes, value.bytes)
    case _                => false
  override def hashCode(): Int  = java.util.Arrays.hashCode(bytes)
  override def toString: String = "Aes128Key(<redacted>)"

object Aes128Key:
  /** Copies exactly 16 bytes into protected key material. */
  def from(bytes: Array[Byte]): Either[CryptoError, Aes128Key] =
    Either.cond(
      bytes.length == 16,
      Aes128Key(bytes.clone()),
      CryptoError.InvalidKeyLength(bytes.length)
    )

/** A 128-bit CBC initialization vector. */
final class InitializationVector private (private[crypto] val bytes: Array[Byte]):
  override def equals(other: Any): Boolean = other match
    case value: InitializationVector => java.security.MessageDigest.isEqual(bytes, value.bytes)
    case _                           => false
  override def hashCode(): Int  = java.util.Arrays.hashCode(bytes)
  override def toString: String = "InitializationVector(<redacted>)"

object InitializationVector:
  /** Parses the RFC hexadecimal `IV` attribute, which must contain 128 bits. */
  def parse(value: String): Either[CryptoError, InitializationVector] =
    val hex = value.stripPrefix("0x").stripPrefix("0X")
    if hex.length != 32 || !hex.forall(character => Character.digit(character, 16) >= 0) then
      Left(CryptoError.InvalidIv(value))
    else Right(InitializationVector(hex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray))

  /**
   * Derives the implicit IV by placing the media sequence in the low 64 bits of a 128-bit
   * big-endian value, as required by RFC 8216 §5.2.
   */
  def fromMediaSequence(sequence: MediaSequence): InitializationVector =
    val bytes = Array.fill[Byte](16)(0)
    java.nio.ByteBuffer.wrap(bytes, 8, 8).putLong(sequence.value)
    InitializationVector(bytes)

/** Typed cryptographic input or provider failure without secret material. */
enum CryptoError:
  case InvalidKeyLength(actual: Int)
  case InvalidIv(value: String)
  case EncryptionFailed(cause: Throwable)
  case DecryptionFailed(cause: Throwable)

/**
 * Whole-segment AES-128-CBC encryption defined by
 * [[https://www.rfc-editor.org/rfc/rfc8216#section-5 RFC 8216 §5]].
 *
 * Java's `PKCS5Padding` provider name implements the PKCS#7 padding required for AES's 16-byte
 * block size. Returned arrays are newly allocated.
 */
object Aes128SegmentCipher:
  /** Encrypts one complete Media Segment. */
  def encrypt(
      plaintext: Array[Byte],
      key: Aes128Key,
      iv: InitializationVector
  ): Either[CryptoError, Array[Byte]] =
    transform(Cipher.ENCRYPT_MODE, plaintext, key, iv).left.map(CryptoError.EncryptionFailed(_))

  /** Decrypts and verifies padding for one complete encrypted Media Segment. */
  def decrypt(
      ciphertext: Array[Byte],
      key: Aes128Key,
      iv: InitializationVector
  ): Either[CryptoError, Array[Byte]] =
    transform(Cipher.DECRYPT_MODE, ciphertext, key, iv).left.map(CryptoError.DecryptionFailed(_))

  private def transform(
      mode: Int,
      input: Array[Byte],
      key: Aes128Key,
      iv: InitializationVector
  ): Either[Throwable, Array[Byte]] = Try:
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(mode, SecretKeySpec(key.bytes, "AES"), IvParameterSpec(iv.bytes))
    cipher.doFinal(input)
  .toEither
