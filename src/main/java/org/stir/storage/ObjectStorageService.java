package org.stir.storage;

import io.minio.*;
import java.io.InputStream;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * S3-compatible object storage (MinIO locally; any real S3-compatible provider in production via
 * config - never one specific cloud vendor). Objects are never served directly from here: every
 * read goes through AttachmentService's own tenant/ownership authorization first (see
 * AttachmentController) - the bucket itself is never exposed to a browser.
 */
@Component
public class ObjectStorageService {
    private final MinioClient client;
    private final String bucket;

    public ObjectStorageService(Environment env) {
        String endpoint = env.getProperty("storage.endpoint", "http://minio:9000");
        String accessKey = env.getProperty("storage.access-key", "");
        String secretKey = env.getProperty("storage.secret-key", "");
        this.bucket = env.getProperty("storage.bucket", "stir-attachments");
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        ensureBucket();
    }

    private void ensureBucket() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        } catch (Exception ex) { throw new StorageException("Object storage bucket check/create failed", ex); }
    }

    public void put(String key, InputStream data, long size, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                .stream(data, size, -1L).contentType(contentType).build());
        } catch (Exception ex) { throw new StorageException("Failed to store object " + key, ex); }
    }

    public InputStream get(String key) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception ex) { throw new StorageException("Failed to read object " + key, ex); }
    }

    public void delete(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception ex) { throw new StorageException("Failed to delete object " + key, ex); }
    }

    /** For readiness: is the configured bucket actually reachable right now? */
    public boolean isReachable() {
        try { return client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()); }
        catch (Exception ex) { return false; }
    }
    public String bucket() { return bucket; }

    public static class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) { super(message, cause); }
    }
}
