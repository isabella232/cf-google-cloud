package com.doitintl.gcp.gke;

import com.amazonaws.services.secretsmanager.AWSSecretsManager;
import com.amazonaws.services.secretsmanager.AWSSecretsManagerClientBuilder;
import com.amazonaws.services.secretsmanager.model.*;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.container.v1.ClusterManagerClient;
import com.google.cloud.container.v1.ClusterManagerSettings;
import com.google.container.v1.Cluster;
import com.google.container.v1.Operation;
import software.amazon.cloudformation.proxy.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class CreateHandler extends BaseHandler<CallbackContext> {
  private static final int NUMBER_OF_STATE_POLL_RETRIES = 60;
  private static final int POLL_RETRY_DELAY_IN_MS = 5000;
  private static final String TIMED_OUT_MESSAGE =
      "Timed out waiting for cluster to become available.";
  private AmazonWebServicesClientProxy clientProxy;
  private ClusterManagerClient clusterManagerClient;

  @Override
  public ProgressEvent<ResourceModel, CallbackContext> handleRequest(
      final AmazonWebServicesClientProxy proxy,
      final ResourceHandlerRequest<ResourceModel> request,
      final CallbackContext callbackContext,
      final Logger logger) {
    final ResourceModel model = request.getDesiredResourceState();

    clientProxy = proxy;
    final CallbackContext currentContext =
        callbackContext == null
            ? CallbackContext.builder()
                .stabilizationRetriesRemaining(NUMBER_OF_STATE_POLL_RETRIES)
                .build()
            : callbackContext;

    GoogleCredentials credentials;
    try {
      credentials = getGoogleCredentials(model);
    } catch (IOException e) {
      return ProgressEvent.<ResourceModel, CallbackContext>builder()
          .resourceModel(model)
          .status(OperationStatus.FAILED)
          .build();
    }

    ClusterManagerSettings clusterManagerSettings;
    try {
      clusterManagerSettings =
          ClusterManagerSettings.newBuilder()
              .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
              .build();
      clusterManagerClient = ClusterManagerClient.create(clusterManagerSettings);
    } catch (IOException e) {
      ProgressEvent.<ResourceModel, CallbackContext>builder()
          .resourceModel(model)
          .status(OperationStatus.FAILED)
          .build();
    }

    return createClusterAndUpdateProgress(model, currentContext);
  }

  private ProgressEvent<ResourceModel, CallbackContext> createClusterAndUpdateProgress(
      ResourceModel model, CallbackContext callbackContext) {
    final String operationStateSoFar = callbackContext.getOperationID();
    if (callbackContext.getStabilizationRetriesRemaining() == 0) {
      throw new RuntimeException(TIMED_OUT_MESSAGE);
    }
    Operation operation;
    if (operationStateSoFar != null) {
      operation =
          clusterManagerClient.getOperation(
              model.getProject(), model.getZone(), operationStateSoFar);
      if (operation.getStatus() == Operation.Status.DONE) {
        return ProgressEvent.<ResourceModel, CallbackContext>builder()
            .resourceModel(model)
            .status(OperationStatus.SUCCESS)
            .build();
      } else if (operation.getStatus() == Operation.Status.ABORTING
          || operation.getStatus() == Operation.Status.STATUS_UNSPECIFIED
          || operation.getStatus() == Operation.Status.UNRECOGNIZED) {
        return ProgressEvent.<ResourceModel, CallbackContext>builder()
            .resourceModel(model)
            .status(OperationStatus.FAILED)
            .build();

      } else {
        try {
          Thread.sleep(POLL_RETRY_DELAY_IN_MS);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
        return ProgressEvent.<ResourceModel, CallbackContext>builder()
            .resourceModel(model)
            .status(OperationStatus.IN_PROGRESS)
            .callbackContext(
                CallbackContext.builder()
                    .operationID(UpdateClusterProgress(model, operationStateSoFar))
                    .stabilizationRetriesRemaining(
                        callbackContext.getStabilizationRetriesRemaining() - 1)
                    .build())
            .build();
      }
    } else {
      return ProgressEvent.<ResourceModel, CallbackContext>builder()
          .resourceModel(model)
          .status(OperationStatus.IN_PROGRESS)
          .callbackContext(
              CallbackContext.builder()
                  .operationID(createCluster(model))
                  .stabilizationRetriesRemaining(NUMBER_OF_STATE_POLL_RETRIES)
                  .build())
          .build();
    }
  }

  private String createCluster(ResourceModel model) {
    try {
      Cluster cluster =
          Cluster.newBuilder()
              .setName(model.getName())
              .setInitialNodeCount(model.getInitialNodeCount())
              .build();
      return clusterManagerClient
          .createCluster(model.getProject(), model.getZone(), cluster)
          .getName();
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  private String UpdateClusterProgress(ResourceModel model, String operationID) {
    return clusterManagerClient
        .getOperation(model.getProject(), model.getZone(), operationID)
        .getName();
  }

  private GoogleCredentials getGoogleCredentials(ResourceModel model) throws IOException {
    try {
      String serviceAccount = getSecret(model.getSecret(), model.getSecretRegion());
      InputStream inputStream =
          new ByteArrayInputStream(serviceAccount.getBytes(StandardCharsets.UTF_8));
      return GoogleCredentials.fromStream(inputStream);
    } catch (IOException e) {
      throw e;
    }
  }

  private String getSecret(String secretName, String region) {

    // Create a Secrets Manager client
    AWSSecretsManager client = AWSSecretsManagerClientBuilder.standard().withRegion(region).build();

    String secret, decodedBinarySecret;
    GetSecretValueRequest getSecretValueRequest =
        new GetSecretValueRequest().withSecretId(secretName);
    GetSecretValueResult getSecretValueResult;

    try {
      getSecretValueResult =
          clientProxy.injectCredentialsAndInvoke(getSecretValueRequest, client::getSecretValue);
      // for local testing switch the lines
      // getSecretValueResult = client.getSecretValue(getSecretValueRequest);
    } catch (DecryptionFailureException e) {
      // Secrets Manager can't decrypt the protected secret text using the provided KMS key.
      // Deal with the exception here, and/or rethrow at your discretion.
      throw e;
    } catch (InternalServiceErrorException e) {
      // An error occurred on the server side.
      // Deal with the exception here, and/or rethrow at your discretion.
      throw e;
    } catch (InvalidParameterException e) {
      // You provided an invalid value for a parameter.
      // Deal with the exception here, and/or rethrow at your discretion.
      throw e;
    } catch (InvalidRequestException e) {
      // You provided a parameter value that is not valid for the current state of the resource.
      // Deal with the exception here, and/or rethrow at your discretion.
      throw e;
    } catch (ResourceNotFoundException e) {
      // We can't find the resource that you asked for.
      // Deal with the exception here, and/or rethrow at your discretion.
      throw e;
    }

    // Decrypts secret using the associated KMS CMK.
    // Depending on whether the secret is a string or binary, one of these fields will be populated.
    if (getSecretValueResult.getSecretString() != null) {
      secret = getSecretValueResult.getSecretString();
      return secret;
    } else {
      decodedBinarySecret =
          new String(Base64.getDecoder().decode(getSecretValueResult.getSecretBinary()).array());
      return decodedBinarySecret;
    }
  }
}
