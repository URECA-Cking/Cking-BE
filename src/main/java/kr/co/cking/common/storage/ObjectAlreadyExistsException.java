package kr.co.cking.common.storage;

public class ObjectAlreadyExistsException extends ObjectStorageException {

    public ObjectAlreadyExistsException(String objectKey) {
        super("object already exists: " + objectKey);
    }
}
