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
package com.datastax.oss.streaming.ai;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatMessage;
import com.azure.ai.openai.models.ChatRole;
import com.datastax.oss.streaming.ai.model.config.ChatCompletionsConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.apache.avro.generic.GenericData;
import org.apache.avro.util.Utf8;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.schema.GenericObject;
import org.apache.pulsar.client.api.schema.GenericRecord;
import org.apache.pulsar.client.api.schema.GenericSchema;
import org.apache.pulsar.client.api.schema.KeyValueSchema;
import org.apache.pulsar.client.api.schema.RecordSchemaBuilder;
import org.apache.pulsar.client.api.schema.SchemaBuilder;
import org.apache.pulsar.client.impl.schema.AutoConsumeSchema;
import org.apache.pulsar.common.schema.KeyValue;
import org.apache.pulsar.common.schema.KeyValueEncodingType;
import org.apache.pulsar.common.schema.SchemaInfo;
import org.apache.pulsar.common.schema.SchemaType;
import org.apache.pulsar.functions.api.Record;
import org.mockito.ArgumentCaptor;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class ChatCompletionsStepTest {

  private static final String COMPLETION =
      (""
              + "{"
              + "  'choices': ["
              + "    {"
              + "      'message': {"
              + "        'content': 'result'"
              + "      }"
              + "    }"
              + "  ]"
              + "}")
          .replace("'", "\"");
  private static final ObjectMapper mapper = new ObjectMapper();
  private OpenAIClient openAIClient;

  @BeforeMethod
  void setup() throws Exception {
    openAIClient = mock(OpenAIClient.class);
    when(openAIClient.getChatCompletions(eq("test-model"), any()))
        .thenReturn(mapper.readValue(COMPLETION, ChatCompletions.class));
  }

  @Test
  void testPrimitive() throws Exception {
    Record<GenericObject> record =
        Utils.TestRecord.<GenericObject>builder()
            .key("test-key")
            .value(
                AutoConsumeSchema.wrapPrimitiveObject(
                    "test-message", SchemaType.STRING, new byte[] {}))
            .schema(Schema.STRING)
            .eventTime(42L)
            .topicName("test-input-topic")
            .destinationTopic("test-output-topic")
            .properties(Map.of("test-key", "test-value"))
            .build();

    ArgumentCaptor<ChatCompletionsOptions> captor =
        ArgumentCaptor.forClass(ChatCompletionsOptions.class);
    ChatCompletionsConfig config = new ChatCompletionsConfig();
    config.setModel("test-model");
    config.setMessages(
        List.of(
            new ChatMessage(ChatRole.USER)
                .setContent(
                    "{{ value }} {{ key}} {{ eventTime }} {{ topicName }} {{ destinationTopic }} {{ properties.test-key }}")));
    Utils.process(record, new ChatCompletionsStep(openAIClient, config));
    verify(openAIClient).getChatCompletions(eq("test-model"), captor.capture());

    assertEquals(
        captor.getValue().getMessages().get(0).getContent(),
        "test-message test-key 42 test-input-topic test-output-topic test-value");
  }

  @DataProvider(name = "structuredSchemaTypes")
  public static Object[][] structuredSchemaTypes() {
    return new Object[][] {{SchemaType.AVRO}, {SchemaType.JSON}};
  }

  @Test(dataProvider = "structuredSchemaTypes")
  void testStructured(SchemaType schemaType) throws Exception {
    TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
    RecordSchemaBuilder recordSchemaBuilder = SchemaBuilder.record("record");
    recordSchemaBuilder.field("firstName").type(SchemaType.STRING);
    recordSchemaBuilder.field("lastName").type(SchemaType.STRING);
    recordSchemaBuilder.field("age").type(SchemaType.INT32);
    recordSchemaBuilder.field("date").type(SchemaType.DATE);
    recordSchemaBuilder.field("timestamp").type(SchemaType.TIMESTAMP);
    recordSchemaBuilder.field("time").type(SchemaType.TIME);

    SchemaInfo schemaInfo = recordSchemaBuilder.build(schemaType);
    GenericSchema<GenericRecord> genericSchema = Schema.generic(schemaInfo);

    GenericRecord genericRecord =
        genericSchema
            .newRecordBuilder()
            .set("firstName", "Jane")
            .set("lastName", "Doe")
            .set("age", 42)
            .set("date", (int) LocalDate.of(2023, 1, 2).toEpochDay())
            .set("timestamp", Instant.parse("2023-01-02T23:04:05.006Z").toEpochMilli())
            .set("time", (int) (LocalTime.parse("23:04:05.006").toNanoOfDay() / 1_000_000))
            .build();

    Record<GenericObject> record = new Utils.TestRecord<>(genericSchema, genericRecord, "test-key");

    ChatCompletionsConfig config = new ChatCompletionsConfig();
    config.setModel("test-model");
    config.setMessages(
        List.of(
            new ChatMessage(ChatRole.USER)
                .setContent(
                    "{{ value.firstName }} {{ value.lastName }} {{ value.age }} {{ value.date }} {{ value.timestamp }} {{ value.time }} {{ key }}")));
    Utils.process(record, new ChatCompletionsStep(openAIClient, config));
    ArgumentCaptor<ChatCompletionsOptions> captor =
        ArgumentCaptor.forClass(ChatCompletionsOptions.class);
    verify(openAIClient).getChatCompletions(eq("test-model"), captor.capture());

    assertEquals(
        captor.getValue().getMessages().get(0).getContent(),
        "Jane Doe 42 19359 1672700645006 83045006 test-key");
  }

  @DataProvider(name = "jsonStringSchemas")
  public static Object[][] jsonStringSchemas() {
    return new Object[][] {{Schema.STRING}, {Schema.BYTES}};
  }

  @Test(dataProvider = "jsonStringSchemas")
  void testJsonString(Schema<?> schema) throws Exception {
    TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
    Object json =
        "{\"firstName\":\"Jane\",\"lastName\":\"Doe\",\"age\":42,\"date\":19359,\"timestamp\":1672700645006,\"time\":83045006}";
    if (schema.getSchemaInfo().getType() == SchemaType.BYTES) {
      json = ((String) json).getBytes(StandardCharsets.UTF_8);
    }
    Record<GenericObject> record =
        new Utils.TestRecord<>(
            schema,
            AutoConsumeSchema.wrapPrimitiveObject(
                json, schema.getSchemaInfo().getType(), new byte[] {}),
            "test-key");

    ChatCompletionsConfig config = new ChatCompletionsConfig();
    config.setModel("test-model");
    config.setMessages(
        List.of(
            new ChatMessage(ChatRole.USER)
                .setContent(
                    "{{ value.firstName }} {{ value.lastName }} {{ value.age }} {{ value.date }} {{ value.timestamp }} {{ value.time }} {{ key }}")));
    Utils.process(record, new ChatCompletionsStep(openAIClient, config));

    ArgumentCaptor<ChatCompletionsOptions> captor =
        ArgumentCaptor.forClass(ChatCompletionsOptions.class);
    verify(openAIClient).getChatCompletions(eq("test-model"), captor.capture());

    assertEquals(
        captor.getValue().getMessages().get(0).getContent(),
        "Jane Doe 42 19359 1672700645006 83045006 test-key");
  }

  @Test(dataProvider = "structuredSchemaTypes")
  void testKVStructured(SchemaType schemaType) throws Exception {
    ArgumentCaptor<ChatCompletionsOptions> captor =
        ArgumentCaptor.forClass(ChatCompletionsOptions.class);
    ChatCompletionsConfig config = new ChatCompletionsConfig();
    config.setModel("test-model");
    config.setMessages(
        List.of(
            new ChatMessage(ChatRole.USER)
                .setContent("{{ value.valueField1 }} {{ key.keyField2 }}")));
    Utils.process(
        Utils.createTestStructKeyValueRecord(schemaType),
        new ChatCompletionsStep(openAIClient, config));
    verify(openAIClient).getChatCompletions(eq("test-model"), captor.capture());

    assertEquals(captor.getValue().getMessages().get(0).getContent(), "value1 key2");
  }

  @Test(dataProvider = "jsonStringSchemas")
  void testKVJsonString(Schema<?> schema) throws Exception {
    TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
    Object json =
        "{\"firstName\":\"Jane\",\"lastName\":\"Doe\",\"age\":42,\"date\":19359,\"timestamp\":1672700645006,\"time\":83045006}";
    if (schema.getSchemaInfo().getType() == SchemaType.BYTES) {
      json = ((String) json).getBytes(StandardCharsets.UTF_8);
    }

    Record<GenericObject> record =
        new Utils.TestRecord<>(
            schema,
            AutoConsumeSchema.wrapPrimitiveObject(
                json, schema.getSchemaInfo().getType(), new byte[] {}),
            "test-key");

    ChatCompletionsConfig config = new ChatCompletionsConfig();
    config.setModel("test-model");
    config.setMessages(
        List.of(
            new ChatMessage(ChatRole.USER)
                .setContent(
                    "{{ value.firstName }} {{ value.lastName }} {{ value.age }} {{ value.date }} {{ value.timestamp }} {{ value.time }} {{ key }}")));
    Utils.process(record, new ChatCompletionsStep(openAIClient, config));

    ArgumentCaptor<ChatCompletionsOptions> captor =
        ArgumentCaptor.forClass(ChatCompletionsOptions.class);
    verify(openAIClient).getChatCompletions(eq("test-model"), captor.capture());

    assertEquals(
        captor.getValue().getMessages().get(0).getContent(),
        "Jane Doe 42 19359 1672700645006 83045006 test-key");
  }

  @Test
  void testValueOutput() throws Exception {
    Record<GenericObject> record =
        Utils.TestRecord.<GenericObject>builder()
            .key("test-key")
            .schema(Schema.STRING)
            .value(
                AutoConsumeSchema.wrapPrimitiveObject(
                    "test-message", SchemaType.STRING, new byte[] {}))
            .build();

    ChatCompletionsConfig config = new ChatCompletionsConfig();
    config.setModel("test-model");
    config.setMessages(List.of(new ChatMessage(ChatRole.USER).setContent("content")));
    Record<?> outputRecord = Utils.process(record, new ChatCompletionsStep(openAIClient, config));
    assertEquals(outputRecord.getValue(), "result");
  }

  @Test
  void testKeyOutput() throws Exception {
    ChatCompletionsConfig config = new ChatCompletionsConfig();
    config.setModel("test-model");
    config.setMessages(List.of(new ChatMessage(ChatRole.USER).setContent("content")));
    config.setFieldName("key");
    Record<?> outputRecord =
        Utils.process(
            Utils.createTestAvroKeyValueRecord(), new ChatCompletionsStep(openAIClient, config));
    KeyValueSchema<?, ?> messageSchema = (KeyValueSchema<?, ?>) outputRecord.getSchema();
    KeyValue<?, ?> messageValue = (KeyValue<?, ?>) outputRecord.getValue();

    assertEquals(messageSchema.getKeySchema().getSchemaInfo().getType(), SchemaType.STRING);
    assertEquals(messageValue.getKey(), "result");
  }

  @Test
  void testDestinationTopicOutput() throws Exception {
    Record<GenericObject> record =
        Utils.TestRecord.<GenericObject>builder()
            .key("test-key")
            .value(
                AutoConsumeSchema.wrapPrimitiveObject(
                    "test-message", SchemaType.STRING, new byte[] {}))
            .schema(Schema.STRING)
            .build();
    ChatCompletionsConfig config = new ChatCompletionsConfig();
    config.setModel("test-model");
    config.setMessages(List.of(new ChatMessage(ChatRole.USER).setContent("content")));
    config.setFieldName("destinationTopic");
    Record<?> outputRecord = Utils.process(record, new ChatCompletionsStep(openAIClient, config));
    assertEquals(outputRecord.getDestinationTopic().orElseThrow(), "result");
  }

  @Test
  void testMessageKeyOutput() throws Exception {
    Record<GenericObject> record =
        Utils.TestRecord.<GenericObject>builder()
            .key("test-key")
            .value(
                AutoConsumeSchema.wrapPrimitiveObject(
                    "test-message", SchemaType.STRING, new byte[] {}))
            .schema(Schema.STRING)
            .build();
    ChatCompletionsConfig config = new ChatCompletionsConfig();
    config.setModel("test-model");
    config.setMessages(List.of(new ChatMessage(ChatRole.USER).setContent("content")));
    config.setFieldName("messageKey");
    Record<?> outputRecord = Utils.process(record, new ChatCompletionsStep(openAIClient, config));
    assertEquals(outputRecord.getKey().orElseThrow(), "result");
  }

  @Test
  void testPropertyOutput() throws Exception {
    Record<GenericObject> record =
        Utils.TestRecord.<GenericObject>builder()
            .key("test-key")
            .value(
                AutoConsumeSchema.wrapPrimitiveObject(
                    "test-message", SchemaType.STRING, new byte[] {}))
            .schema(Schema.STRING)
            .build();
    ChatCompletionsConfig config = new ChatCompletionsConfig();
    config.setModel("test-model");
    config.setMessages(List.of(new ChatMessage(ChatRole.USER).setContent("content")));
    config.setFieldName("properties.chat");
    Record<?> outputRecord = Utils.process(record, new ChatCompletionsStep(openAIClient, config));
    assertEquals(outputRecord.getProperties().get("chat"), "result");
  }

  @Test
  void testAvroValueFieldOutput() throws Exception {
    ChatCompletionsConfig config = new ChatCompletionsConfig();
    config.setModel("test-model");
    config.setMessages(List.of(new ChatMessage(ChatRole.USER).setContent("content")));
    config.setFieldName("value.chat");
    Record<?> outputRecord =
        Utils.process(
            Utils.createTestAvroKeyValueRecord(), new ChatCompletionsStep(openAIClient, config));
    KeyValueSchema<?, ?> messageSchema = (KeyValueSchema<?, ?>) outputRecord.getSchema();
    KeyValue<?, ?> messageValue = (KeyValue<?, ?>) outputRecord.getValue();

    GenericData.Record valueAvroRecord =
        Utils.getRecord(messageSchema.getValueSchema(), (byte[]) messageValue.getValue());
    assertEquals(valueAvroRecord.get("chat"), new Utf8("result"));
  }

  @Test
  void testJsonValueFieldOutput() throws Exception {
    ChatCompletionsConfig config = new ChatCompletionsConfig();
    config.setModel("test-model");
    config.setMessages(List.of(new ChatMessage(ChatRole.USER).setContent("content")));
    config.setFieldName("value.chat");
    Record<?> outputRecord =
        Utils.process(
            Utils.createTestJsonKeyValueRecord(), new ChatCompletionsStep(openAIClient, config));
    KeyValueSchema<?, ?> messageSchema = (KeyValueSchema<?, ?>) outputRecord.getSchema();
    KeyValue<?, ?> messageValue = (KeyValue<?, ?>) outputRecord.getValue();

    org.apache.avro.Schema schema =
        (org.apache.avro.Schema) messageSchema.getValueSchema().getNativeSchema().orElseThrow();
    assertEquals(schema.getField("chat").schema().getType(), org.apache.avro.Schema.Type.STRING);
    assertEquals(((JsonNode) messageValue.getValue()).get("chat").asText(), "result");
  }

  @DataProvider(name = "jsonStringFieldOutput")
  public static Object[][] jsonStringFieldOutput() {
    return new Object[][] {
      {Schema.STRING, "{\"name\":\"Jane\"}", "{\"name\":\"Jane\",\"chat\":\"result\"}"},
      {
        Schema.BYTES,
        "{\"name\":\"Jane\"}".getBytes(StandardCharsets.UTF_8),
        "{\"name\":\"Jane\",\"chat\":\"result\"}".getBytes(StandardCharsets.UTF_8)
      }
    };
  }

  @Test(dataProvider = "jsonStringFieldOutput")
  void testJsonStringValueFieldOutput(Schema<?> schema, Object input, Object expected)
      throws Exception {
    Record<GenericObject> record =
        new Utils.TestRecord<>(
            schema,
            AutoConsumeSchema.wrapPrimitiveObject(
                input, schema.getSchemaInfo().getType(), new byte[] {}),
            "test-key");

    ChatCompletionsConfig config = new ChatCompletionsConfig();
    config.setModel("test-model");
    config.setMessages(List.of(new ChatMessage(ChatRole.USER).setContent("content")));
    config.setFieldName("value.chat");
    Record<?> outputRecord = Utils.process(record, new ChatCompletionsStep(openAIClient, config));

    assertEquals(outputRecord.getSchema(), schema);
    assertEquals(outputRecord.getValue(), expected);
  }

  @Test
  void testAvroKeyFieldOutput() throws Exception {
    ChatCompletionsConfig config = new ChatCompletionsConfig();
    config.setModel("test-model");
    config.setMessages(List.of(new ChatMessage(ChatRole.USER).setContent("content")));
    config.setFieldName("key.chat");
    Record<?> outputRecord =
        Utils.process(
            Utils.createTestAvroKeyValueRecord(), new ChatCompletionsStep(openAIClient, config));
    KeyValueSchema<?, ?> messageSchema = (KeyValueSchema<?, ?>) outputRecord.getSchema();
    KeyValue<?, ?> messageValue = (KeyValue<?, ?>) outputRecord.getValue();

    GenericData.Record keyAvroRecord =
        Utils.getRecord(messageSchema.getKeySchema(), (byte[]) messageValue.getKey());
    assertEquals(keyAvroRecord.get("chat"), new Utf8("result"));
  }

  @Test
  void testJsonKeyFieldOutput() throws Exception {
    ChatCompletionsConfig config = new ChatCompletionsConfig();
    config.setModel("test-model");
    config.setMessages(List.of(new ChatMessage(ChatRole.USER).setContent("content")));
    config.setFieldName("key.chat");
    Record<?> outputRecord =
        Utils.process(
            Utils.createTestJsonKeyValueRecord(), new ChatCompletionsStep(openAIClient, config));
    KeyValueSchema<?, ?> messageSchema = (KeyValueSchema<?, ?>) outputRecord.getSchema();
    KeyValue<?, ?> messageValue = (KeyValue<?, ?>) outputRecord.getValue();

    org.apache.avro.Schema schema =
        (org.apache.avro.Schema) messageSchema.getKeySchema().getNativeSchema().orElseThrow();
    assertEquals(schema.getField("chat").schema().getType(), org.apache.avro.Schema.Type.STRING);
    assertEquals(((JsonNode) messageValue.getKey()).get("chat").asText(), "result");
  }

  @Test(dataProvider = "jsonStringFieldOutput")
  void testJsonStringKeyFieldOutput(Schema<?> schema, Object input, Object expected)
      throws Exception {
    Schema<? extends KeyValue<?, Integer>> keyValueSchema =
        Schema.KeyValue(schema, Schema.INT32, KeyValueEncodingType.SEPARATED);

    KeyValue<Object, Integer> keyValue = new KeyValue<>(input, 42);

    Record<GenericObject> record =
        new Utils.TestRecord<>(
            keyValueSchema,
            AutoConsumeSchema.wrapPrimitiveObject(keyValue, SchemaType.KEY_VALUE, new byte[] {}),
            "test-key");

    ChatCompletionsConfig config = new ChatCompletionsConfig();
    config.setModel("test-model");
    config.setMessages(List.of(new ChatMessage(ChatRole.USER).setContent("content")));
    config.setFieldName("key.chat");
    Record<?> outputRecord = Utils.process(record, new ChatCompletionsStep(openAIClient, config));
    KeyValueSchema<?, ?> messageSchema = (KeyValueSchema<?, ?>) outputRecord.getSchema();
    KeyValue<?, ?> messageValue = (KeyValue<?, ?>) outputRecord.getValue();

    assertEquals(messageSchema.getKeySchema(), schema);
    assertEquals(messageValue.getKey(), expected);
  }
}
