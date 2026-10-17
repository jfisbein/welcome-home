package welcomehome.crypto;

/**
 * A ciphertext could not be decrypted: it was encrypted for another owner, it was altered, or its key is no
 * longer in the keyset.
 */
public class DecryptionFailedException extends RuntimeException {

    DecryptionFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
