/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.assembly.internal;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.slf4j.Logger;

/**
 * Sets the values of an XML configuration on an object, for archivers and container descriptor handlers. It replaces
 * the Plexus {@code BasicComponentConfigurator}, which cannot be used in a Maven 4 plugin: it needs Guice classes that
 * neither the plugin realm nor, for a Maven 4 plugin, the Plexus container provide ("NoClassDefFoundError:
 * com/google/inject/spi/TypeConverter"). It supports what such configurations use: a setter or a field per child
 * element, with string, number, boolean, enum, {@link File} and {@link Path} values, and repeated or nested
 * child elements for collections and arrays of those.
 */
public class BeanConfigurator {
    private final Logger logger;

    public BeanConfigurator(Logger logger) {
        this.logger = logger;
    }

    public void configure(Object bean, Xpp3Dom configuration, ExpressionEvaluator evaluator)
            throws BeanConfigurationException {
        for (Xpp3Dom child : configuration.getChildren()) {
            configureProperty(bean, child, evaluator);
        }
    }

    private void configureProperty(Object bean, Xpp3Dom child, ExpressionEvaluator evaluator)
            throws BeanConfigurationException {
        final String property = toCamelCase(child.getName());
        final String setterName = "set" + Character.toUpperCase(property.charAt(0)) + property.substring(1);

        for (Method method : bean.getClass().getMethods()) {
            if (method.getName().equals(setterName) && method.getParameterCount() == 1) {
                final Object value = convert(child, method.getParameterTypes()[0], evaluator);
                try {
                    method.invoke(bean, value);
                } catch (InvocationTargetException e) {
                    throw new BeanConfigurationException(
                            "Cannot set '" + child.getName() + "' on "
                                    + bean.getClass().getName(),
                            e.getCause());
                } catch (IllegalAccessException e) {
                    throw new BeanConfigurationException(
                            "Cannot set '" + child.getName() + "' on "
                                    + bean.getClass().getName(),
                            e);
                }
                logger.debug("  (s) " + property + " = " + value);
                return;
            }
        }

        for (Class<?> type = bean.getClass(); type != null; type = type.getSuperclass()) {
            try {
                final Field field = type.getDeclaredField(property);
                field.setAccessible(true);
                final Object value = convert(child, field.getType(), evaluator);
                field.set(bean, value);
                logger.debug("  (f) " + property + " = " + value);
                return;
            } catch (NoSuchFieldException e) {
                // try the superclass
            } catch (IllegalAccessException | RuntimeException e) {
                throw new BeanConfigurationException(
                        "Cannot set '" + child.getName() + "' on "
                                + bean.getClass().getName(),
                        e);
            }
        }

        throw new BeanConfigurationException("Cannot find a setter or a field for '" + child.getName() + "' in "
                + bean.getClass().getName());
    }

    private Object convert(Xpp3Dom element, Class<?> type, ExpressionEvaluator evaluator)
            throws BeanConfigurationException {
        if (type.isArray() && element.getChildCount() > 0) {
            final Class<?> componentType = type.getComponentType();
            final Object array = java.lang.reflect.Array.newInstance(componentType, element.getChildCount());
            for (int i = 0; i < element.getChildCount(); i++) {
                java.lang.reflect.Array.set(array, i, convert(element.getChild(i), componentType, evaluator));
            }
            return array;
        }
        if (Collection.class.isAssignableFrom(type)) {
            final List<Object> list = new ArrayList<>();
            for (Xpp3Dom item : element.getChildren()) {
                list.add(evaluate(item.getValue(), evaluator));
            }
            return list;
        }

        final Object evaluated = evaluate(element.getValue(), evaluator);
        if (evaluated == null) {
            return null;
        }
        final String value = evaluated.toString();

        if (type.isInstance(evaluated) && !(evaluated instanceof CharSequence)) {
            return evaluated;
        } else if (type == String.class || type == CharSequence.class || type == Object.class) {
            return value;
        } else if (type == boolean.class || type == Boolean.class) {
            return Boolean.valueOf(value.trim());
        } else if (type == int.class || type == Integer.class) {
            return Integer.valueOf(value.trim());
        } else if (type == long.class || type == Long.class) {
            return Long.valueOf(value.trim());
        } else if (type == File.class) {
            return evaluator.alignToBaseDirectory(new File(value.trim()));
        } else if (type == Path.class) {
            return evaluator.alignToBaseDirectory(new File(value.trim())).toPath();
        } else if (type.isEnum()) {
            for (Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(value.trim())) {
                    return constant;
                }
            }
            throw new BeanConfigurationException("'" + value + "' is not a value of " + type.getName());
        }
        throw new BeanConfigurationException("Unsupported type " + type.getName() + " for '" + element.getName() + "'");
    }

    private Object evaluate(String expression, ExpressionEvaluator evaluator) throws BeanConfigurationException {
        if (expression == null) {
            return null;
        }
        try {
            return evaluator.evaluate(expression);
        } catch (ExpressionEvaluationException e) {
            throw new BeanConfigurationException("Cannot evaluate '" + expression + "'", e);
        }
    }

    /**
     * Element names like {@code duplicate-behavior} stand for the property {@code duplicateBehavior}.
     */
    private static String toCamelCase(String name) {
        final StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : name.toCharArray()) {
            if (c == '-') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }

    /**
     * The configuration could not be applied.
     */
    public static class BeanConfigurationException extends Exception {
        private static final long serialVersionUID = 1L;

        public BeanConfigurationException(String message) {
            super(message);
        }

        public BeanConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
