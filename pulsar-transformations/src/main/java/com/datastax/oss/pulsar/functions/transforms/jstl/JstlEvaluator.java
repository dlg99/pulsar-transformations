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
package com.datastax.oss.pulsar.functions.transforms.jstl;

import com.datastax.oss.pulsar.functions.transforms.TransformContext;
import de.odysseus.el.ExpressionFactoryImpl;
import de.odysseus.el.util.SimpleContext;
import java.util.Map;
import javax.el.ELContext;
import javax.el.ExpressionFactory;
import javax.el.ValueExpression;

public class JstlEvaluator<T> {

  private static final ExpressionFactory FACTORY =
      new ExpressionFactoryImpl(System.getProperties(), new NullableTypeConverter());
  private final ValueExpression valueExpression;
  private final ELContext expressionContext;

  private final Class<?> type;

  public JstlEvaluator(String expression, Class<?> type) {
    this.type = type;
    this.expressionContext = new SimpleContext();
    this.valueExpression = FACTORY.createValueExpression(expressionContext, expression, type);
  }

  public T evaluate(TransformContext transformContext) {
    JstlTransformContextAdapter adapter = new JstlTransformContextAdapter(transformContext);
    FACTORY
        .createValueExpression(expressionContext, "${key}", Object.class)
        .setValue(expressionContext, adapter.getKey());
    FACTORY
        .createValueExpression(expressionContext, "${value}", Object.class)
        .setValue(expressionContext, adapter.getValue());

    // Register message headers as top level fields
    FACTORY
        .createValueExpression(expressionContext, "${messageKey}", String.class)
        .setValue(expressionContext, adapter.getHeader().get("messageKey"));
    FACTORY
        .createValueExpression(expressionContext, "${topicName}", String.class)
        .setValue(expressionContext, adapter.getHeader().get("topicName"));
    FACTORY
        .createValueExpression(expressionContext, "${destinationTopic}", String.class)
        .setValue(expressionContext, adapter.getHeader().get("destinationTopic"));
    FACTORY
        .createValueExpression(expressionContext, "${eventTime}", Long.class)
        .setValue(expressionContext, adapter.getHeader().get("eventTime"));
    FACTORY
        .createValueExpression(expressionContext, "${properties}", Map.class)
        .setValue(expressionContext, adapter.getHeader().get("properties"));
    return (T) this.valueExpression.getValue(expressionContext);
  }
}