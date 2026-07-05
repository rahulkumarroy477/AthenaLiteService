package org.example.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;

@Service
public class S3Service {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3Service(@Value("${aws.s3.region}") String region,
                     @Value("${aws.s3.bucket}") String bucket) {
        this.bucket = bucket;
        this.s3 = S3Client.builder().region(Region.of(region)).build();
        this.presigner = S3Presigner.builder().region(Region.of(region)).build();
    }

    public void uploadFile(String key, byte[] content, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                .bucket(bucket).key(key).contentType(contentType)
                .build(), RequestBody.fromBytes(content));
    }

    public byte[] downloadFile(String key) {
        return s3.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(bucket).key(key)
                .build()).asByteArray();
    }

    public void deleteFile(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    public void copyFile(String sourceKey, String destKey) {
        s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucket).sourceKey(sourceKey)
                .destinationBucket(bucket).destinationKey(destKey).build());
    }

    public String getBucket() { return bucket; }

    public String generatePresignedPutUrl(String key, String contentType) {
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket).key(key).contentType(contentType).build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(5))
                .putObjectRequest(putRequest).build();
        return presigner.presignPutObject(presignRequest).url().toString();
    }
}
