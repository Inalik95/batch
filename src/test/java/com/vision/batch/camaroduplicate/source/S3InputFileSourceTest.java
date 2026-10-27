package com.vision.batch.camaroduplicate.source;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3InputFileSourceTest {

    @TempDir
    Path workDir;

    private S3Client s3;
    private S3InputFileSource source;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        source = new S3InputFileSource(s3, "vision-bucket", "camaro/in", "camaro/archive/", null, workDir);
    }

    private static S3Object obj(String key) {
        return S3Object.builder().key(key).build();
    }

    @Test
    void list_matchesGlobOnFileName_ignoresSubPrefixes_followsPagination_sorted() {
        when(s3.listObjectsV2(argThat((ListObjectsV2Request r) -> r != null && r.continuationToken() == null)))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(obj("camaro/in/FUSION_J_20261027.csv"), obj("camaro/in/other.txt"),
                                obj("camaro/in/old/FUSION_J_20261001.csv"), obj("camaro/in/"))
                        .isTruncated(true).nextContinuationToken("page2").build());
        when(s3.listObjectsV2(argThat((ListObjectsV2Request r) -> r != null && "page2".equals(r.continuationToken()))))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(obj("camaro/in/FUSION_J_20261026.csv"))
                        .isTruncated(false).build());

        assertThat(source.list("FUSION_J_*.csv"))
                .containsExactly("camaro/in/FUSION_J_20261026.csv", "camaro/in/FUSION_J_20261027.csv");

        ArgumentCaptor<ListObjectsV2Request> captor = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3, org.mockito.Mockito.times(2)).listObjectsV2(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(r -> {
            assertThat(r.bucket()).isEqualTo("vision-bucket");
            assertThat(r.prefix()).isEqualTo("camaro/in/");
        });
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void fetch_downloadsToWorkDir_withoutLeavingPartFile() throws Exception {
        byte[] content = "OBSOLETE;SURVIVOR\nA1;B1\n".getBytes(StandardCharsets.UTF_8);
        doAnswer(inv -> {
            ResponseTransformer transformer = inv.getArgument(1);
            return transformer.transform(GetObjectResponse.builder().build(),
                    AbortableInputStream.create(new ByteArrayInputStream(content)));
        }).when(s3).getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));

        Path local = source.fetch("camaro/in/FUSION_J_20261027.csv");

        assertThat(local).isEqualTo(workDir.resolve("FUSION_J_20261027.csv"));
        assertThat(Files.readAllBytes(local)).isEqualTo(content);
        assertThat(workDir.resolve("FUSION_J_20261027.csv.part")).doesNotExist();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void fetch_failure_leavesNothingBehind() {
        doAnswer(inv -> {
            throw NoSuchKeyException.builder().statusCode(404).message("gone").build();
        }).when(s3).getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));

        assertThatThrownBy(() -> source.fetch("camaro/in/missing.csv")).isInstanceOf(NoSuchKeyException.class);
        assertThat(workDir.resolve("missing.csv.part")).doesNotExist();
        assertThat(workDir.resolve("missing.csv")).doesNotExist();
    }

    @Test
    void archive_copiesThenDeletes() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

        source.archive("camaro/in/FUSION_J_20261027.csv");

        InOrder order = inOrder(s3);
        order.verify(s3).copyObject(argThat((CopyObjectRequest r) -> r != null
                && "camaro/in/FUSION_J_20261027.csv".equals(r.sourceKey())
                && "camaro/archive/FUSION_J_20261027.csv".equals(r.destinationKey())
                && "vision-bucket".equals(r.destinationBucket())));
        order.verify(s3).deleteObject(argThat((DeleteObjectRequest r) -> r != null
                && "camaro/in/FUSION_J_20261027.csv".equals(r.key())));
    }

    @Test
    void archive_isIdempotent_whenSourceAlreadyGone() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().statusCode(404).build());

        source.archive("camaro/in/FUSION_J_20261027.csv");

        verify(s3, never()).copyObject(any(CopyObjectRequest.class));
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void markAlreadyProcessed_usesSuffix() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

        source.markAlreadyProcessed("camaro/in/F.csv");

        verify(s3).copyObject(argThat((CopyObjectRequest r) -> r != null
                && "camaro/archive/F.csv.already-processed".equals(r.destinationKey())));
    }

    @Test
    void release_deletesLocalCopyOnly() throws Exception {
        Path local = Files.writeString(workDir.resolve("F.csv"), "x");

        source.release("camaro/in/F.csv", local);

        assertThat(local).doesNotExist();
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void rejectsArchivePrefixEqualToInputPrefix() {
        assertThatThrownBy(() -> new S3InputFileSource(s3, "b", "camaro/in/", "/camaro/in", null, workDir))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizePrefix() {
        assertThat(S3InputFileSource.normalizePrefix(null)).isEmpty();
        assertThat(S3InputFileSource.normalizePrefix("")).isEmpty();
        assertThat(S3InputFileSource.normalizePrefix("/a/b")).isEqualTo("a/b/");
        assertThat(S3InputFileSource.normalizePrefix("a/b/")).isEqualTo("a/b/");
    }
}
