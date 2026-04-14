package com.ariadne.collector;

import software.amazon.awssdk.awscore.exception.AwsServiceException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

public abstract class BaseCollector implements ResourceCollector {

    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MILLIS = 200L;

    protected <T> T withRetry(Supplier<T> supplier) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return supplier.get();
            } catch (RuntimeException exception) {
                lastFailure = exception;
                if (!isThrottle(exception) || attempt == MAX_ATTEMPTS) {
                    throw exception;
                }
                sleepQuietly(BASE_BACKOFF_MILLIS * attempt);
            }
        }
        throw lastFailure;
    }

    protected Map<String, String> toTagMap(List<? extends software.amazon.awssdk.services.ec2.model.Tag> tags) {
        return toTagMap(tags, software.amazon.awssdk.services.ec2.model.Tag::key, software.amazon.awssdk.services.ec2.model.Tag::value);
    }

    protected <T> Map<String, String> toTagMap(
            List<T> tags,
            Function<T, String> keyExtractor,
            Function<T, String> valueExtractor
    ) {
        var tagMap = new LinkedHashMap<String, String>();
        if (tags == null) {
            return tagMap;
        }
        for (var tag : tags) {
            tagMap.put(keyExtractor.apply(tag), valueExtractor.apply(tag));
        }
        return tagMap;
    }

    protected String inferName(Map<String, String> tags, String fallback) {
        return tags.getOrDefault("Name", fallback);
    }

    protected String inferEnvironment(Map<String, String> tags) {
        for (var key : List.of("environment", "Environment", "env", "Env")) {
            var value = tags.get(key);
            if (value != null && !value.isBlank()) {
                return value.toLowerCase();
            }
        }
        return "unknown";
    }

    protected String ec2Arn(AwsCollectContext context, String resourcePath) {
        return "arn:aws:ec2:%s:%s:%s".formatted(context.region(), context.accountId(), resourcePath);
    }

    protected String s3Arn(String bucketName) {
        return "arn:aws:s3:::%s".formatted(bucketName);
    }

    protected boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    protected <T> List<List<T>> chunked(List<T> values, int chunkSize) {
        var chunks = new java.util.ArrayList<List<T>>();
        if (values == null || values.isEmpty()) {
            return chunks;
        }
        for (int index = 0; index < values.size(); index += chunkSize) {
            chunks.add(values.subList(index, Math.min(values.size(), index + chunkSize)));
        }
        return chunks;
    }

    private boolean isThrottle(RuntimeException exception) {
        if (!(exception instanceof AwsServiceException awsException)) {
            return false;
        }
        var errorCode = awsException.awsErrorDetails() == null
                ? ""
                : awsException.awsErrorDetails().errorCode();
        return errorCode != null && errorCode.toLowerCase().contains("throttl");
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Collector retry was interrupted", interruptedException);
        }
    }
}
