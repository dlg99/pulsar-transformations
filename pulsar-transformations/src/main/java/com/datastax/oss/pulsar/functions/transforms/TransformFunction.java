/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.pulsar.functions.transforms;

import static com.datastax.oss.streaming.ai.embeddings.AbstractHuggingFaceEmbeddingService.DLJ_BASE_URL;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.NonAzureOpenAIKeyCredential;
import com.azure.core.credential.AzureKeyCredential;
import com.datastax.oss.driver.shaded.guava.common.base.Strings;
import com.datastax.oss.streaming.ai.CastStep;
import com.datastax.oss.streaming.ai.ChatCompletionsStep;
import com.datastax.oss.streaming.ai.ComputeAIEmbeddingsStep;
import com.datastax.oss.streaming.ai.ComputeStep;
import com.datastax.oss.streaming.ai.DropFieldStep;
import com.datastax.oss.streaming.ai.DropStep;
import com.datastax.oss.streaming.ai.FlattenStep;
import com.datastax.oss.streaming.ai.JsonNodeSchema;
import com.datastax.oss.streaming.ai.MergeKeyValueStep;
import com.datastax.oss.streaming.ai.QueryStep;
import com.datastax.oss.streaming.ai.TransformContext;
import com.datastax.oss.streaming.ai.TransformStep;
import com.datastax.oss.streaming.ai.UnwrapKeyValueStep;
import com.datastax.oss.streaming.ai.datasource.AstraDBDataSource;
import com.datastax.oss.streaming.ai.datasource.QueryStepDataSource;
import com.datastax.oss.streaming.ai.embeddings.AbstractHuggingFaceEmbeddingService;
import com.datastax.oss.streaming.ai.embeddings.EmbeddingsService;
import com.datastax.oss.streaming.ai.embeddings.HuggingFaceEmbeddingService;
import com.datastax.oss.streaming.ai.embeddings.HuggingFaceRestEmbeddingService;
import com.datastax.oss.streaming.ai.embeddings.OpenAIEmbeddingsService;
import com.datastax.oss.streaming.ai.jstl.predicate.JstlPredicate;
import com.datastax.oss.streaming.ai.jstl.predicate.StepPredicatePair;
import com.datastax.oss.streaming.ai.model.ComputeField;
import com.datastax.oss.streaming.ai.model.ComputeFieldType;
import com.datastax.oss.streaming.ai.model.TransformSchemaType;
import com.datastax.oss.streaming.ai.model.config.CastConfig;
import com.datastax.oss.streaming.ai.model.config.ChatCompletionsConfig;
import com.datastax.oss.streaming.ai.model.config.ComputeAIEmbeddingsConfig;
import com.datastax.oss.streaming.ai.model.config.ComputeConfig;
import com.datastax.oss.streaming.ai.model.config.DataSourceConfig;
import com.datastax.oss.streaming.ai.model.config.DropFieldsConfig;
import com.datastax.oss.streaming.ai.model.config.FlattenConfig;
import com.datastax.oss.streaming.ai.model.config.HuggingFaceConfig;
import com.datastax.oss.streaming.ai.model.config.OpenAIConfig;
import com.datastax.oss.streaming.ai.model.config.OpenAIProvider;
import com.datastax.oss.streaming.ai.model.config.QueryConfig;
import com.datastax.oss.streaming.ai.model.config.StepConfig;
import com.datastax.oss.streaming.ai.model.config.TransformStepConfig;
import com.datastax.oss.streaming.ai.model.config.UnwrapKeyValueConfig;
import com.datastax.oss.streaming.ai.util.TransformFunctionUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.urn.URNFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.schema.GenericObject;
import org.apache.pulsar.client.api.schema.KeyValueSchema;
import org.apache.pulsar.common.schema.KeyValue;
import org.apache.pulsar.common.schema.KeyValueEncodingType;
import org.apache.pulsar.functions.api.Context;
import org.apache.pulsar.functions.api.Function;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.functions.api.utils.FunctionRecord;

/**
 * <code>TransformFunction</code> is a {@link Function} that provides an easy way to apply a set of
 * usual basic transformations to the data.
 *
 * <p>It provides the following transformations:
 *
 * <ul>
 *   <li><code>cast</code>: modifies the key or value schema to a target compatible schema passed in
 *       the <code>schema-type</code> argument. This PR only enables <code>STRING</code>
 *       schema-type. The <code>part</code> argument allows to choose on which part to apply between
 *       <code>key</code> and <code>value</code>. If <code>part</code> is null or absent the
 *       transformations applies to both the key and value.
 *   <li><code>drop-fields</code>: drops fields given as a string list in parameter <code>fields
 *       </code>. The <code>part</code> argument allows to choose on which part to apply between
 *       <code>key</code> and <code>value</code>. If <code>part</code> is null or absent the
 *       transformations applies to both the key and value. Currently only AVRO is supported.
 *   <li><code>merge-key-value</code>: merges the fields of KeyValue records where both the key and
 *       value are structured types of the same schema type. Currently only AVRO is supported.
 *   <li><code>unwrap-key-value</code>: if the record is a KeyValue, extract the KeyValue's value
 *       and make it the record value. If parameter <code>unwrapKey</code> is present and set to
 *       <code>true</code>, extract the KeyValue's key instead.
 *   <li><code>flatten</code>: flattens a nested structure selected in the <code>part</code> by
 *       concatenating nested field names with a <code>delimiter</code> and populating them as top
 *       level fields. <code>
 *       delimiter</code> defaults to '_'. <code>part</code> could be any of <code>key</code> or
 *       <code>value</code>. If not specified, flatten will apply to key and value.
 *   <li><code>drop</code>: drops the message from further processing. Use in conjunction with
 *       <code>when</code> to selectively drop messages.
 *   <li><code>compute</code>: dynamically calculates <code>fields</code> values in the key, value
 *       or header. Each field has a <code>name</code> to represents a new or existing field (in
 *       this case, it will be overwritten). The value of the fields is evaluated by the <code>
 *       expression</code> and respect the <code>type</code>. Supported types are [INT32, INT64,
 *       FLOAT, DOUBLE, BOOLEAN, DATE, TIME, DATETIME]. Each field is marked as nullable by default.
 *       To mark the field as non-nullable in the output schema, set <code>optional</code> to false.
 * </ul>
 *
 * <p>The <code>TransformFunction</code> reads its configuration as Json from the {@link Context}
 * <code>userConfig</code> in the format:
 *
 * <pre><code class="lang-json">
 * {
 *   "steps": [
 *     {
 *       "type": "cast", "schema-type": "STRING"
 *     },
 *     {
 *       "type": "drop-fields", "fields": ["keyField1", "keyField2"], "part": "key"
 *     },
 *     {
 *       "type": "merge-key-value"
 *     },
 *     {
 *       "type": "unwrap-key-value"
 *     },
 *     {
 *       "type": "flatten", "delimiter" : "_" "part" : "value", "when": "value.field == 'value'"
 *     },
 *     {
 *       "type": "drop", "when": "value.field == 'value'"
 *     },
 *     {
 *       "type": "compute", "fields": [{"name": "value.new-field", "expression": "key.existing-field == 'value'", "type": "BOOLEAN"}]
 *     }
 *   ]
 * }
 * </code></pre>
 *
 * @see <a href="https://github.com/apache/pulsar/issues/15902">PIP-173 : Create a built-in Function
 *     implementing the most common basic transformations</a>
 */
@Slf4j
public class TransformFunction
    implements Function<GenericObject, Record<GenericObject>>, TransformStep {

  private static final List<String> FIELD_NAMES =
      Arrays.asList("value", "key", "destinationTopic", "messageKey", "topicName", "eventTime");
  private final List<StepPredicatePair> steps = new ArrayList<>();
  private TransformStepConfig transformConfig;
  private OpenAIClient openAIClient;
  private QueryStepDataSource dataSource;

  @Override
  public void initialize(Context context) {
    Map<String, Object> userConfigMap = context.getUserConfigMap();
    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    JsonNode jsonNode = mapper.convertValue(userConfigMap, JsonNode.class);

    URNFactory urnFactory =
        urn -> {
          try {
            URL absoluteURL = Thread.currentThread().getContextClassLoader().getResource(urn);
            return absoluteURL.toURI();
          } catch (Exception ex) {
            return null;
          }
        };
    JsonSchemaFactory factory =
        JsonSchemaFactory.builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V4))
            .objectMapper(mapper)
            .addUrnFactory(urnFactory)
            .build();
    SchemaValidatorsConfig jsonSchemaConfig = new SchemaValidatorsConfig();
    jsonSchemaConfig.setLosslessNarrowing(true);
    InputStream is =
        Thread.currentThread().getContextClassLoader().getResourceAsStream("config-schema.yaml");

    JsonSchema schema = factory.getSchema(is, jsonSchemaConfig);
    Set<ValidationMessage> errors = schema.validate(jsonNode);

    if (errors.size() != 0) {
      if (!jsonNode.hasNonNull("steps")) {
        throw new IllegalArgumentException("Missing config 'steps' field");
      }
      JsonNode steps = jsonNode.get("steps");
      if (!steps.isArray()) {
        throw new IllegalArgumentException("Config 'steps' field must be an array");
      }
      String errorMessage = null;
      try {
        for (JsonNode step : steps) {
          String type = step.get("type").asText();
          JsonSchema stepSchema =
              factory.getSchema(
                  String.format(
                      "{\"$ref\": \"config-schema.yaml#/components/schemas/%s\"}",
                      kebabToPascal(type)));

          errorMessage =
              stepSchema
                  .validate(step)
                  .stream()
                  .findFirst()
                  .map(v -> String.format("Invalid '%s' step config: %s", type, v))
                  .orElse(null);
          if (errorMessage != null) {
            break;
          }
        }
      } catch (Exception e) {
        log.debug("Exception during steps validation, ignoring", e);
      }

      if (errorMessage != null) {
        throw new IllegalArgumentException(errorMessage);
      }

      errors
          .stream()
          .findFirst()
          .ifPresent(
              validationMessage -> {
                throw new IllegalArgumentException(
                    "Configuration validation failed: " + validationMessage);
              });
    }

    transformConfig = mapper.convertValue(userConfigMap, TransformStepConfig.class);
    openAIClient = buildOpenAIClient(transformConfig.getOpenai());
    dataSource = buildDataSource(transformConfig.getDatasource());

    TransformStep transformStep;
    for (StepConfig step : transformConfig.getSteps()) {
      switch (step.getType()) {
        case "drop-fields":
          transformStep = newRemoveFieldFunction((DropFieldsConfig) step);
          break;
        case "cast":
          transformStep =
              newCastFunction((CastConfig) step, transformConfig.isAttemptJsonConversion());
          break;
        case "merge-key-value":
          transformStep = new MergeKeyValueStep();
          break;
        case "unwrap-key-value":
          transformStep = newUnwrapKeyValueFunction((UnwrapKeyValueConfig) step);
          break;
        case "flatten":
          transformStep = newFlattenFunction((FlattenConfig) step);
          break;
        case "drop":
          transformStep = new DropStep();
          break;
        case "compute":
          transformStep = newComputeFieldFunction((ComputeConfig) step);
          break;
        case "compute-ai-embeddings":
          transformStep = newComputeAIEmbeddings((ComputeAIEmbeddingsConfig) step);
          break;
        case "ai-chat-completions":
          transformStep = newChatCompletionsFunction((ChatCompletionsConfig) step);
          break;
        case "query":
          transformStep = newQuery((QueryConfig) step);
          break;
        default:
          throw new IllegalArgumentException("Invalid step type: " + step.getType());
      }
      steps.add(
          new StepPredicatePair(
              transformStep, step.getWhen() == null ? null : new JstlPredicate(step.getWhen())));
    }
  }

  @Override
  public void close() throws Exception {
    if (dataSource != null) {
      dataSource.close();
    }
    for (StepPredicatePair pair : steps) {
      pair.getTransformStep().close();
    }
  }

  @Override
  public Record<GenericObject> process(GenericObject input, Context context) throws Exception {
    Object nativeObject = input.getNativeObject();
    if (log.isDebugEnabled()) {
      Record<?> currentRecord = context.getCurrentRecord();
      log.debug("apply to {} {}", input, nativeObject);
      log.debug(
          "record with schema {} version {} {}",
          currentRecord.getSchema(),
          currentRecord.getMessage().orElseThrow().getSchemaVersion(),
          currentRecord);
    }

    TransformContext transformContext =
        newTransformContext(context, nativeObject, transformConfig.isAttemptJsonConversion());
    process(transformContext);
    return send(context, transformContext);
  }

  public static TransformContext newTransformContext(
      Context context, Object value, boolean attemptJsonConversion) {
    Record<?> currentRecord = context.getCurrentRecord();
    TransformContext transformContext = new TransformContext();
    transformContext.setInputTopic(currentRecord.getTopicName().orElse(null));
    transformContext.setOutputTopic(currentRecord.getDestinationTopic().orElse(null));
    transformContext.setKey(currentRecord.getKey().orElse(null));
    transformContext.setEventTime(currentRecord.getEventTime().orElse(null));

    if (currentRecord.getProperties() != null) {
      transformContext.setProperties(new HashMap<>(currentRecord.getProperties()));
    }

    Schema<?> schema = currentRecord.getSchema();
    if (schema instanceof KeyValueSchema && value instanceof KeyValue) {
      KeyValueSchema<?, ?> kvSchema = (KeyValueSchema<?, ?>) schema;
      KeyValue<?, ?> kv = (KeyValue<?, ?>) value;
      Schema<?> keySchema = kvSchema.getKeySchema();
      Schema<?> valueSchema = kvSchema.getValueSchema();
      transformContext.setKeySchemaType(pulsarSchemaToTransformSchemaType(keySchema));
      transformContext.setKeyNativeSchema(getNativeSchema(keySchema));
      transformContext.setKeyObject(
          keySchema.getSchemaInfo().getType().isStruct()
              ? ((GenericObject) kv.getKey()).getNativeObject()
              : kv.getKey());
      transformContext.setValueSchemaType(pulsarSchemaToTransformSchemaType(valueSchema));
      transformContext.setValueNativeSchema(getNativeSchema(valueSchema));
      transformContext.setValueObject(
          valueSchema.getSchemaInfo().getType().isStruct()
              ? ((GenericObject) kv.getValue()).getNativeObject()
              : kv.getValue());
      transformContext
          .getCustomContext()
          .put("keyValueEncodingType", kvSchema.getKeyValueEncodingType());
    } else {
      transformContext.setValueSchemaType(pulsarSchemaToTransformSchemaType(schema));
      transformContext.setValueNativeSchema(getNativeSchema(schema));
      transformContext.setValueObject(value);
    }
    if (attemptJsonConversion) {
      transformContext.setKeyObject(
          TransformFunctionUtil.attemptJsonConversion(transformContext.getKeyObject()));
      transformContext.setValueObject(
          TransformFunctionUtil.attemptJsonConversion(transformContext.getValueObject()));
    }
    return transformContext;
  }

  private static Object getNativeSchema(Schema<?> schema) {
    if (schema == null) {
      return null;
    }
    return schema.getNativeSchema().orElse(null);
  }

  private static TransformSchemaType pulsarSchemaToTransformSchemaType(Schema<?> schema) {
    if (schema == null) {
      return null;
    }
    switch (schema.getSchemaInfo().getType()) {
      case INT8:
        return TransformSchemaType.INT8;
      case INT16:
        return TransformSchemaType.INT16;
      case INT32:
        return TransformSchemaType.INT32;
      case INT64:
        return TransformSchemaType.INT64;
      case FLOAT:
        return TransformSchemaType.FLOAT;
      case DOUBLE:
        return TransformSchemaType.DOUBLE;
      case BOOLEAN:
        return TransformSchemaType.BOOLEAN;
      case STRING:
        return TransformSchemaType.STRING;
      case BYTES:
        return TransformSchemaType.BYTES;
      case DATE:
        return TransformSchemaType.DATE;
      case TIME:
        return TransformSchemaType.TIME;
      case TIMESTAMP:
        return TransformSchemaType.TIMESTAMP;
      case INSTANT:
        return TransformSchemaType.INSTANT;
      case LOCAL_DATE:
        return TransformSchemaType.LOCAL_DATE;
      case LOCAL_TIME:
        return TransformSchemaType.LOCAL_TIME;
      case LOCAL_DATE_TIME:
        return TransformSchemaType.LOCAL_DATE_TIME;
      case JSON:
        return TransformSchemaType.JSON;
      case AVRO:
        return TransformSchemaType.AVRO;
      case PROTOBUF:
        return TransformSchemaType.PROTOBUF;
      default:
        throw new IllegalArgumentException(
            "Unsupported schema type " + schema.getSchemaInfo().getType());
    }
  }

  @Override
  public void process(TransformContext transformContext) throws Exception {
    for (StepPredicatePair pair : steps) {
      TransformStep step = pair.getTransformStep();
      Predicate<TransformContext> predicate = pair.getPredicate();
      if (predicate == null || predicate.test(transformContext)) {
        step.process(transformContext);
      }
    }
  }

  public static Record<GenericObject> send(Context context, TransformContext transformContext)
      throws IOException {
    if (transformContext.isDropCurrentRecord()) {
      return null;
    }
    transformContext.convertAvroToBytes();
    transformContext.convertMapToStringOrBytes();

    Schema outputSchema;
    Object outputObject;
    if (transformContext.getKeySchemaType() != null) {
      KeyValueEncodingType keyValueEncodingType =
          (KeyValueEncodingType) transformContext.getCustomContext().get("keyValueEncodingType");
      outputSchema =
          Schema.KeyValue(
              buildSchema(
                  transformContext.getKeySchemaType(), transformContext.getKeyNativeSchema()),
              buildSchema(
                  transformContext.getValueSchemaType(), transformContext.getValueNativeSchema()),
              keyValueEncodingType != null ? keyValueEncodingType : KeyValueEncodingType.INLINE);
      Object outputKeyObject = transformContext.getKeyObject();
      Object outputValueObject = transformContext.getValueObject();
      outputObject = new KeyValue<>(outputKeyObject, outputValueObject);
    } else {
      outputSchema =
          buildSchema(
              transformContext.getValueSchemaType(), transformContext.getValueNativeSchema());
      outputObject = transformContext.getValueObject();
    }

    if (log.isDebugEnabled()) {
      log.debug("output {} schema {}", outputObject, outputSchema);
    }

    FunctionRecord.FunctionRecordBuilder<GenericObject> recordBuilder =
        context
            .newOutputRecordBuilder(outputSchema)
            .destinationTopic(transformContext.getOutputTopic())
            .value(outputObject)
            .properties(transformContext.getProperties());

    if (transformContext.getKeySchemaType() == null && transformContext.getKey() != null) {
      recordBuilder.key(transformContext.getKey());
    }

    return recordBuilder.build();
  }

  private static Schema<?> buildSchema(TransformSchemaType schemaType, Object nativeSchema) {
    if (schemaType == null) {
      throw new IllegalArgumentException("Schema type should not be null.");
    }
    switch (schemaType) {
      case INT8:
        return Schema.INT8;
      case INT16:
        return Schema.INT16;
      case INT32:
        return Schema.INT32;
      case INT64:
        return Schema.INT64;
      case FLOAT:
        return Schema.FLOAT;
      case DOUBLE:
        return Schema.DOUBLE;
      case BOOLEAN:
        return Schema.BOOL;
      case STRING:
        return Schema.STRING;
      case BYTES:
        return Schema.BYTES;
      case DATE:
        return Schema.DATE;
      case TIME:
        return Schema.TIME;
      case TIMESTAMP:
        return Schema.TIMESTAMP;
      case INSTANT:
        return Schema.INSTANT;
      case LOCAL_DATE:
        return Schema.LOCAL_DATE;
      case LOCAL_TIME:
        return Schema.LOCAL_TIME;
      case LOCAL_DATE_TIME:
        return Schema.LOCAL_DATE_TIME;
      case AVRO:
        return Schema.NATIVE_AVRO(nativeSchema);
      case JSON:
        return new JsonNodeSchema((org.apache.avro.Schema) nativeSchema);
      default:
        throw new IllegalArgumentException("Unsupported schema type " + schemaType);
    }
  }

  private static String kebabToPascal(String kebab) {
    return Pattern.compile("(?:^|-)(.)").matcher(kebab).replaceAll(mr -> mr.group(1).toUpperCase());
  }

  public static DropFieldStep newRemoveFieldFunction(DropFieldsConfig config) {
    DropFieldStep.DropFieldStepBuilder builder = DropFieldStep.builder();
    if (config.getPart() != null) {
      if (config.getPart().equals("key")) {
        builder.keyFields(config.getFields());
      } else {
        builder.valueFields(config.getFields());
      }
    } else {
      builder.keyFields(config.getFields()).valueFields(config.getFields());
    }
    return builder.build();
  }

  public static CastStep newCastFunction(CastConfig config, boolean attemptJsonConversion) {
    String schemaTypeParam = config.getSchemaType();
    TransformSchemaType schemaType = TransformSchemaType.valueOf(schemaTypeParam);
    CastStep.CastStepBuilder builder =
        CastStep.builder().attemptJsonConversion(attemptJsonConversion);
    if (config.getPart() != null) {
      if (config.getPart().equals("key")) {
        builder.keySchemaType(schemaType);
      } else {
        builder.valueSchemaType(schemaType);
      }
    } else {
      builder.keySchemaType(schemaType).valueSchemaType(schemaType);
    }
    return builder.build();
  }

  public static FlattenStep newFlattenFunction(FlattenConfig config) {
    FlattenStep.FlattenStepBuilder builder = FlattenStep.builder();
    if (config.getPart() != null) {
      builder.part(config.getPart());
    }
    if (config.getDelimiter() != null) {
      builder.delimiter(config.getDelimiter());
    }
    return builder.build();
  }

  private static TransformStep newComputeFieldFunction(ComputeConfig config) {
    List<ComputeField> fieldList = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    config
        .getFields()
        .forEach(
            field -> {
              if (seen.contains(field.getName())) {
                throw new IllegalArgumentException(
                    "Duplicate compute field name detected: " + field.getName());
              }
              if (field.getType() == ComputeFieldType.DATE
                  && ("value".equals(field.getName()) || "key".equals(field.getName()))) {
                throw new IllegalArgumentException(
                    "The compute operation cannot apply the type DATE to the message value or key. "
                        + "Please consider using the types TIMESTAMP or INSTANT instead and follow with a 'cast' "
                        + "to SchemaType.DATE operation.");
              }
              seen.add(field.getName());
              ComputeFieldType type =
                  "destinationTopic".equals(field.getName())
                          || "messageKey".equals(field.getName())
                          || field.getName().startsWith("properties.")
                      ? ComputeFieldType.STRING
                      : field.getType();
              fieldList.add(
                  ComputeField.builder()
                      .scopedName(field.getName())
                      .expression(field.getExpression())
                      .type(type)
                      .optional(field.isOptional())
                      .build());
            });
    return ComputeStep.builder().fields(fieldList).build();
  }

  @SneakyThrows
  private TransformStep newComputeAIEmbeddings(ComputeAIEmbeddingsConfig config) {
    String targetSvc = config.getService();
    HuggingFaceConfig huggingConfig = transformConfig.getHuggingface();
    if (Strings.isNullOrEmpty(targetSvc)) {
      targetSvc = ComputeAIEmbeddingsConfig.SupportedServices.OPENAI.name();
      if (openAIClient == null && huggingConfig != null) {
        targetSvc = ComputeAIEmbeddingsConfig.SupportedServices.HUGGINGFACE.name();
      }
    }

    ComputeAIEmbeddingsConfig.SupportedServices service =
        ComputeAIEmbeddingsConfig.SupportedServices.valueOf(targetSvc.toUpperCase());

    final EmbeddingsService embeddingService;
    switch (service) {
      case OPENAI:
        embeddingService = new OpenAIEmbeddingsService(openAIClient, config.getModel());
        break;
      case HUGGINGFACE:
        Objects.requireNonNull(huggingConfig, "huggingface config is required");
        switch (huggingConfig.getProvider()) {
          case LOCAL:
            AbstractHuggingFaceEmbeddingService.HuggingFaceConfig.HuggingFaceConfigBuilder builder =
                AbstractHuggingFaceEmbeddingService.HuggingFaceConfig.builder()
                    .options(config.getOptions())
                    .arguments(config.getArguments())
                    .modelUrl(config.getModelUrl());
            String modelUrl = config.getModelUrl();
            if (!Strings.isNullOrEmpty(config.getModel())) {
              builder.modelName(config.getModel());

              // automatically build the model URL if not provided
              if (!Strings.isNullOrEmpty(modelUrl)) {
                modelUrl = DLJ_BASE_URL + config.getModel();
                log.info("Automatically computed model URL {}", modelUrl);
              }
            }
            builder.modelUrl(modelUrl);

            embeddingService = new HuggingFaceEmbeddingService(builder.build());
            break;
          case API:
            Objects.requireNonNull(config.getModel(), "model name is required");
            HuggingFaceRestEmbeddingService.HuggingFaceApiConfig.HuggingFaceApiConfigBuilder
                apiBuilder =
                    HuggingFaceRestEmbeddingService.HuggingFaceApiConfig.builder()
                        .accessKey(huggingConfig.getAccessKey())
                        .model(config.getModel());

            if (!Strings.isNullOrEmpty(huggingConfig.getApiUrl())) {
              apiBuilder.hfUrl(huggingConfig.getApiUrl());
            }
            if (config.getOptions() != null && config.getOptions().size() > 0) {
              apiBuilder.options(config.getOptions());
            } else {
              apiBuilder.options(Map.of("wait_for_model", "true"));
            }

            embeddingService = new HuggingFaceRestEmbeddingService(apiBuilder.build());
            break;
          default:
            throw new IllegalArgumentException(
                "Unsupported HuggingFace service type: " + huggingConfig.getProvider());
        }
        break;
      default:
        throw new IllegalArgumentException("Unsupported service: " + service);
    }

    return new ComputeAIEmbeddingsStep(
        config.getText(), config.getEmbeddingsFieldName(), embeddingService);
  }

  private static UnwrapKeyValueStep newUnwrapKeyValueFunction(UnwrapKeyValueConfig config) {
    return new UnwrapKeyValueStep(config.isUnwrapKey());
  }

  private TransformStep newChatCompletionsFunction(ChatCompletionsConfig config) {
    if (openAIClient == null) {
      throw new IllegalArgumentException("The OpenAI client must be configured for this step");
    }
    return new ChatCompletionsStep(openAIClient, config);
  }

  private TransformStep newQuery(QueryConfig config) {
    config
        .getFields()
        .forEach(
            field -> {
              if (!FIELD_NAMES.contains(field)
                  && !field.startsWith("value.")
                  && !field.startsWith("key.")
                  && !field.startsWith("properties")) {
                throw new IllegalArgumentException(
                    String.format("Invalid field name for query step: %s", field));
              }
            });
    return QueryStep.builder()
        .outputFieldName(config.getOutputField())
        .query(config.getQuery())
        .fields(config.getFields())
        .dataSource(dataSource)
        .build();
  }

  protected OpenAIClient buildOpenAIClient(OpenAIConfig openAIConfig) {
    if (openAIConfig == null) {
      return null;
    }
    OpenAIClientBuilder openAIClientBuilder = new OpenAIClientBuilder();
    if (openAIConfig.getProvider() == OpenAIProvider.AZURE) {
      openAIClientBuilder.credential(new AzureKeyCredential(openAIConfig.getAccessKey()));
    } else {
      openAIClientBuilder.credential(new NonAzureOpenAIKeyCredential(openAIConfig.getAccessKey()));
    }
    if (openAIConfig.getUrl() != null) {
      openAIClientBuilder.endpoint(openAIConfig.getUrl());
    }
    return openAIClientBuilder.buildClient();
  }

  protected QueryStepDataSource buildDataSource(DataSourceConfig dataSourceConfig) {
    if (dataSourceConfig == null) {
      return new QueryStepDataSource() {};
    }
    QueryStepDataSource dataSource;
    switch (dataSourceConfig.getService() + "") {
      case "astra":
        dataSource = new AstraDBDataSource();
        break;
      default:
        throw new IllegalArgumentException("Invalid service type " + dataSourceConfig.getService());
    }
    dataSource.initialize(dataSourceConfig);
    return dataSource;
  }
}
