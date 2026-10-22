package kr.co.cking.common.storage;

public class ObjectNotFoundException extends ObjectStorageException {

    public ObjectNotFoundException(String objectKey) {
        super("object not found: " + objectKey);
    }
}
