package kr.co.cking.common.storage;

class InMemoryObjectStorageTest extends ObjectStorageContractTest {

    private final InMemoryObjectStorage storage = new InMemoryObjectStorage(MAX_PRESIGNED_TTL);

    @Override
    protected ObjectStorage storage() {
        return storage;
    }
}
