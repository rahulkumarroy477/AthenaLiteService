package org.example.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Service
public class SqsService {

    private final SqsClient sqsClient;
    private final String queueUrl;

    public SqsService(@Value("${aws.sqs.query-queue-url}") String queueUrl,
                      @Value("${aws.s3.region}") String region) {
        this.queueUrl = queueUrl;
        this.sqsClient = SqsClient.builder().region(Region.of(region)).build();
    }

    public void sendMessage(String messageBody) {
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(messageBody)
                .build());
    }
}
