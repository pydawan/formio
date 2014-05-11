/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.formio;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.formio.binding.ArrayUtils;
import net.formio.binding.BoundValuesInfo;
import net.formio.binding.FilledData;
import net.formio.binding.InstanceHoldingInstantiator;
import net.formio.binding.Instantiator;
import net.formio.data.RequestContext;
import net.formio.format.Formatter;
import net.formio.security.PasswordGenerator;
import net.formio.security.TokenMissingException;
import net.formio.servlet.HttpServletRequestParams;
import net.formio.upload.RequestProcessingError;
import net.formio.upload.UploadedFile;
import net.formio.validation.ConstraintViolationMessage;
import net.formio.validation.ValidationResult;

/**
 * Default implementation of {@link FormMapping}. Immutable when not filled.
 * After the filling, new instance of mapping is created and its immutability 
 * depends on the character of filled data.
 * 
 * @author Radek Beran
 */
class BasicFormMapping<T> implements FormMapping<T> {

	static final String ALLOWED_TOKEN_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_@#$%^&*";
	final String path;
	final Class<T> dataClass;
	final Instantiator<T> instantiator;
	final Config config;
	final boolean userDefinedConfig;
	final Object filledObject;
	
	/** Mapping simple property names to fields. */
	final Map<String, FormField> fields;
	/** Mapping simple property names to nested mappings. Property name is a part of full path of nested mapping. */
	final Map<String, FormMapping<?>> nested;
	final ValidationResult validationResult;
	final boolean required;
	final boolean secured;
	final String formId;

	/**
	 * Construct the mapping from given builder.
	 * @param builder
	 */
	BasicFormMapping(BasicFormMappingBuilder<T> builder) {
		if (builder.config == null) throw new IllegalArgumentException("config cannot be null");
		this.config = builder.config;
		this.userDefinedConfig = builder.userDefinedConfig;
		this.path = builder.path;
		this.dataClass = builder.dataClass;
		this.formId = builder.path + "-" + builder.dataClass.getSimpleName();
		this.instantiator = builder.instantiator;
		this.filledObject = builder.filledObject;
		this.secured = builder.secured;
		if (this.dataClass == null) throw new IllegalStateException("data class must be filled before configuring fields");
		this.fields = configuredFields(builder.fields, builder.config);
		this.validationResult = builder.validationResult;
		// propagate user defined or default config to nested mappings if they have
		// not their own user defined configs
		Map<String, FormMapping<?>> newNestedMappings = new LinkedHashMap<String, FormMapping<?>>();
		for (Map.Entry<String, FormMapping<?>> e : builder.nested.entrySet()) {
			Config nestedMappingConfig = chooseConfigForNestedMapping(e.getValue(), builder.config);
			boolean required = nestedMappingConfig.getBeanValidator().isRequired(builder.dataClass, e.getKey());
			newNestedMappings.put(e.getKey(), e.getValue().withConfig(nestedMappingConfig, required));
		}
		this.nested = Collections.unmodifiableMap(newNestedMappings);
		this.required = false;
	}
	
	/**
	 * Returns copy with given path prefix prepended.
	 * @param src
	 * @param pathPrefix
	 */
	BasicFormMapping(BasicFormMapping<T> src, String pathPrefix) {
		if (pathPrefix == null) throw new IllegalArgumentException("pathPrefix cannot be null");
		this.config = src.getConfig();
		this.userDefinedConfig = src.isUserDefinedConfig();
		String newMappingPath = null;
		if (!pathPrefix.isEmpty()) {
			newMappingPath = pathPrefix + Forms.PATH_SEP + src.getName();
		} else {
			newMappingPath = src.getName();
		}
		this.path = newMappingPath;
		this.dataClass = src.getDataClass();
		this.formId = src.formId;
		this.instantiator = src.getInstantiator();
		this.filledObject = src.getFilledObject();
		this.secured = src.secured;
		Map<String, FormField> newFields = new LinkedHashMap<String, FormField>();
		for (Map.Entry<String, FormField> e : src.getFields().entrySet()) {
			// copy of field with given prefix prepended
			FormField field = new FormFieldImpl(e.getValue(), pathPrefix); // copy constructor
			if (!field.getName().startsWith(newMappingPath + Forms.PATH_SEP))
				throw new IllegalStateException("Field name '" + field.getName() + "' must start with prefix '" + newMappingPath + ".'");
			newFields.put(e.getKey(), field); // key must be a simple property name (it is not changing)
		}
		this.fields = Collections.unmodifiableMap(newFields);
		final Map<String, FormMapping<?>> newNestedMappings = new LinkedHashMap<String, FormMapping<?>>();
		for (Map.Entry<String, FormMapping<?>> e : src.getNested().entrySet()) {
			newNestedMappings.put(e.getKey(), e.getValue().withPathPrefix(pathPrefix));
		}
		this.nested = Collections.unmodifiableMap(newNestedMappings);
		this.validationResult = src.getValidationResult();
		this.required = src.required;
	}
	
	BasicFormMapping(BasicFormMapping<T> src, int index, String pathPrefix) {
		if (pathPrefix == null) throw new IllegalArgumentException("pathPrefix cannot be null");
		this.config = src.getConfig();
		this.userDefinedConfig = src.isUserDefinedConfig();
		if (!src.path.startsWith(pathPrefix))
			throw new IllegalStateException("Mapping path '" + src.path + "' must start with prefix '" + pathPrefix + ".'");
		String newMappingPath = pathPrefix + "[" + index + "]" + src.path.substring(pathPrefix.length());
		this.path = newMappingPath;
		this.dataClass = src.dataClass;
		this.formId = src.formId;
		this.instantiator = src.instantiator;
		this.filledObject = src.filledObject;
		this.secured = src.secured;
		Map<String, FormField> newFields = new LinkedHashMap<String, FormField>();
		for (Map.Entry<String, FormField> e : src.fields.entrySet()) {
			// copy of field with given prefix prepended
			FormField field = new FormFieldImpl(e.getValue(), index, pathPrefix); // copy constructor
			newFields.put(e.getKey(), field); // key must be a simple property name (it is not changing)
		}
		this.fields = Collections.unmodifiableMap(newFields);
		Map<String, FormMapping<?>> newNestedMappings = new LinkedHashMap<String, FormMapping<?>>();
		for (Map.Entry<String, FormMapping<?>> e : src.nested.entrySet()) {
			newNestedMappings.put(e.getKey(), e.getValue().withIndexAfterPathPrefix(index, pathPrefix));
		}
		this.nested = newNestedMappings;
		this.validationResult = src.validationResult;
		this.required = src.required;
	}
	
	/**
	 * Returns copy with given config.
	 * @param src
	 * @param config
	 * @param required
	 */
	BasicFormMapping(BasicFormMapping<T> src, Config config, boolean required) {
		if (config == null) throw new IllegalArgumentException("config cannot be null");
		this.config = config;
		this.userDefinedConfig = true;
		this.path = src.path;
		this.dataClass = src.dataClass;
		this.formId = src.formId;
		this.instantiator = src.instantiator;
		if (this.dataClass == null) throw new IllegalStateException("data class must be filled before configuring fields");
		this.fields = configuredFields(src.fields, config);
		this.validationResult = src.validationResult;
		this.filledObject = src.filledObject;
		this.secured = src.secured;
		Map<String, FormMapping<?>> newNestedMappings = new LinkedHashMap<String, FormMapping<?>>();
		for (Map.Entry<String, FormMapping<?>> e : src.nested.entrySet()) {
			Config nestedMappingConfig = chooseConfigForNestedMapping(e.getValue(), config);
			boolean req = nestedMappingConfig.getBeanValidator().isRequired(src.dataClass, e.getKey());
			newNestedMappings.put(e.getKey(), e.getValue().withConfig(nestedMappingConfig, req));
		}
		this.nested = Collections.unmodifiableMap(newNestedMappings);
		this.required = required;
	}

	@Override
	public String getName() {
		return path;
	}

	@Override
	public Class<T> getDataClass() {
		return dataClass;
	}
	
	@Override
	public Instantiator<T> getInstantiator() {
		return instantiator;
	}

	@Override
	public ValidationResult getValidationResult() {
		return validationResult;
	}
	
	/**
	 * Returns form fields. Can be used in template to construct markup of form fields.
	 * @return
	 */
	@Override
	public Map<String, FormField> getFields() {
		return fields;
	}
	
	/**
	 * Returns nested mapping for nested complex objects.
	 * @return
	 */
	@Override
	public Map<String, FormMapping<?>> getNested() {
		Map<String, FormMapping<?>> mappingsMap = new LinkedHashMap<String, FormMapping<?>>();
		for (Map.Entry<String, FormMapping<?>> entry : nested.entrySet()) {
			mappingsMap.put(entry.getKey(), entry.getValue());
		}
		return Collections.unmodifiableMap(mappingsMap);
	}
	
	@Override
	public List<FormMapping<T>> getList() {
		return Collections.<FormMapping<T>>emptyList();
	}
	
	@Override
	public BasicFormMapping<T> fill(FormData<T> editedObj, Locale locale, RequestContext ctx) {
		return fillInternal(editedObj, locale, ctx).build(this.getConfig());
	}
	
	@Override
	public BasicFormMapping<T> fill(FormData<T> editedObj, Locale locale) {
		return fill(editedObj, locale, null);
	}
	
	@Override
	public BasicFormMapping<T> fill(FormData<T> editedObj, RequestContext ctx) {
		return fill(editedObj, getDefaultLocale(), ctx);
	}
	
	@Override
	public BasicFormMapping<T> fill(FormData<T> editedObj) {
		return fill(editedObj, getDefaultLocale());
	}
	
	@Override
	public FormData<T> bind(ParamsProvider paramsProvider, Locale locale, Class<?>... validationGroups) {
		return bind(paramsProvider, locale, (RequestContext)null, validationGroups);
	}
	
	@Override
	public FormData<T> bind(ParamsProvider paramsProvider, Locale locale, RequestContext ctx, Class<?>... validationGroups) {
		return bind(paramsProvider, locale, (T)null, ctx, validationGroups);
	}
	
	@Override
	public FormData<T> bind(ParamsProvider paramsProvider, Class<?>... validationGroups) {
		return bind(paramsProvider, (RequestContext)null, validationGroups);
	}
	
	@Override
	public FormData<T> bind(ParamsProvider paramsProvider, RequestContext ctx, Class<?>... validationGroups) {
		return bind(paramsProvider, getDefaultLocale(), ctx, validationGroups);
	}
	
	@Override
	public FormData<T> bind(ParamsProvider paramsProvider, T instance, Class<?>... validationGroups) {
		return bind(paramsProvider, instance, (RequestContext)null, validationGroups);
	}
	
	@Override
	public FormData<T> bind(ParamsProvider paramsProvider, T instance, RequestContext ctx, Class<?>... validationGroups) {
		return bind(paramsProvider, getDefaultLocale(), instance, ctx, validationGroups);
	}
	
	@Override
	public FormData<T> bind(ParamsProvider paramsProvider, Locale locale, T instance, Class<?>... validationGroups) {
		return bind(paramsProvider, locale, instance, (RequestContext)null, validationGroups);
	}

	@Override
	public FormData<T> bind(ParamsProvider paramsProvider, Locale locale, T instance, RequestContext ctx, Class<?>... validationGroups) {
		if (paramsProvider == null) throw new IllegalArgumentException("paramsProvider cannot be null");
		if (ctx == null && paramsProvider instanceof HttpServletRequestParams) {
			// fallback to ctx retrieved from HttpServletRequestParams, so the user need not to specify ctx explicitly for bind method
			ctx = ((HttpServletRequestParams)paramsProvider).getRequestContext();
		}
		
		verifyAuthTokenIfSecured(paramsProvider, ctx, false);
		
		final RequestProcessingError error = paramsProvider.getRequestError();
		Map<String, BoundValuesInfo> values = prepareValuesToBindForFields(paramsProvider, locale);
		
		// binding (and validating) data from paramsProvider to objects for nested mappings
		// and adding it to available values to bind
		Map<String, FormData<?>> nestedFormData = loadDataForMappings(nested, paramsProvider, locale, instance, validationGroups);
		for (Map.Entry<String, FormData<?>> e : nestedFormData.entrySet()) {
			values.put(e.getKey(), BoundValuesInfo.getInstance(
				new Object[] { e.getValue().getData() } , 
				(String)null, 
				(Formatter<Object>)null,
				locale));
		}
		
		// binding data from "values" to resulting object for this mapping
		Instantiator<T> instantiator = this.instantiator;
		if (instance != null) {
			// use instance already prepared by client which the client wish to fill
			instantiator = new InstanceHoldingInstantiator<T>(instance);
		}
		final FilledData<T> filledObject = this.getConfig().getBinder().bindToNewInstance(this.dataClass, instantiator, values);
		
		// validation of resulting object for this mapping
		List<RequestProcessingError> requestFailures = new ArrayList<RequestProcessingError>();
		if (error != null) {
			requestFailures.add(error);
		}
		ValidationResult validationRep = this.getConfig().getBeanValidator().validate(
			filledObject.getData(), 
			this.path, 
			requestFailures, 
			flatten(filledObject.getPropertyBindErrors().values()),
			locale,
			validationGroups);
		final Map<String, Set<ConstraintViolationMessage>> fieldMsgs = cloneFieldMessages(validationRep.getFieldMessages());
		
		// gather validation messages from nested mappings
		Set<ConstraintViolationMessage> globalMsgs = new LinkedHashSet<ConstraintViolationMessage>(validationRep.getGlobalMessages());
		for (FormData<?> formData : nestedFormData.values()) {
			fieldMsgs.putAll(formData.getValidationResult().getFieldMessages());
			globalMsgs.addAll(formData.getValidationResult().getGlobalMessages());
		}
		return new FormData<T>(filledObject.getData(), new ValidationResult(fieldMsgs, globalMsgs));
	}

	@Override
	public String getLabelKey() {
		return FormUtils.labelKeyForName(this.path);
	}
	
	/**
	 * Object filled in this mapping.
	 * @return
	 */
	@Override
	public Object getFilledObject() {
		return this.filledObject;
	}
	
	@Override
	public Config getConfig() {
		return config;
	}
	
	@Override
	public boolean isUserDefinedConfig() {
		return userDefinedConfig;
	}
	
	@Override
	public String toString() {
		return toString("");
	}
	
	/**
	 * Returns copy of this mapping with new path that has given prefix prepended.
	 * Given prefix is applied to all nested mappings recursively.
	 * @param pathPrefix
	 * @return
	 */
	@Override
	public BasicFormMapping<T> withPathPrefix(String pathPrefix) {
		return new BasicFormMapping<T>(this, pathPrefix);
	}
	
	@Override
	public BasicFormMapping<T> withIndexAfterPathPrefix(int index, String prefix) {
		return new BasicFormMapping<T>(this, index, prefix);
	}
	
	@Override
	public BasicFormMapping<T> withConfig(Config config, boolean required) {
		return new BasicFormMapping<T>(this, config, required);
	}
	
	@Override
	public String toString(String indent) {
		StringBuilder sb = new StringBuilder();
		sb.append(indent + path + " : " + getDataClass().getSimpleName() + " {");
		boolean firstBlock = true;
		if (fields != null && !fields.isEmpty()) {
			sb.append("\n" + indent + "  fields {");
			boolean first = true;
			for (FormField f : fields.values()) {
				if (!first) {
					sb.append(",");
				} else first = false;
				sb.append("\n    " + indent + f);
			}
			sb.append("\n" + indent + "  }");
			firstBlock = false;
		}
		if (nested != null && !nested.isEmpty()) {
			if (!firstBlock) sb.append(", ");
			sb.append("\n" + indent + "  nested {");
			boolean first = true;
			for (FormMapping<?> nm : nested.values()) {
				if (!first) {
					sb.append(",");
				} else first = false;
				sb.append("\n" + nm.toString(indent + "    "));
			}
			sb.append("\n" + indent + "  }");
			firstBlock = false;
		}
		if (getList() != null && !getList().isEmpty()) {
			if (!firstBlock) {  // NOPMD by Radek on 2.3.14 18:33
				sb.append(", ");
			}
			sb.append("\n" + indent + "  list {");
			boolean first = true;
			for (FormMapping<?> m : getList()) {
				if (!first) {
					sb.append(",");
				} else {
					first = false;
				}
				sb.append("\n" + m.toString(indent + "    "));
			}
			sb.append("\n" + indent + "  }");
			firstBlock = false;
		}
		sb.append("\n" + indent + "}");
		return sb.toString();
	}
	
	@Override
	public boolean isRequired() {
		return this.required;
	}
	
	void verifyAuthTokenIfSecured(ParamsProvider paramsProvider, RequestContext ctx, boolean listMapping) {
		if (isRootMapping() && this.secured) {
			if (listMapping) {
				throw new UnsupportedOperationException("Verification of authorization token is not supported "
					+ "in root list mapping. Please create SINGLE root mapping with nested list mapping.");
			}
			if (ctx == null) {
				throw new IllegalStateException(RequestContext.class.getSimpleName() + " is required when the form is " + 
					"defined as secured. Please specify not null context in bind method.");
			}
			String token = "";
			String value = paramsProvider.getParamValue(this.path + Forms.PATH_SEP + Forms.AUTH_TOKEN_FIELD_NAME);
			if (value != null) {
				token = value;
			}
			if ("".equals(token)) {
				throw new TokenMissingException("Unauthorized attempt. Authorization token is missing! It should be posted as " + Forms.AUTH_TOKEN_FIELD_NAME + 
					" field. Maybe this is blocked CSRF attempt or the required field with token is not rendered in the form correctly.");
			}
			String genSecret = ctx.getUserRelatedStorage().getAndDelete(this.formId);
			String secret = ctx.getRequestSecret(genSecret);
			// InvalidTokenException is thrown for invalid token
			this.config.getTokenAuthorizer().validateToken(token, secret);
		}
	}
	
	Config chooseConfigForNestedMapping(FormMapping<?> mapping, Config outerConfig) {
		Config nestedMappingConfig = mapping.getConfig();
		if (!mapping.isUserDefinedConfig() && outerConfig != null) {
			// config for nested mapping was not explicitly defined, we will pass outer config to nested mapping
			nestedMappingConfig = outerConfig;
		}
		return nestedMappingConfig;
	}
	
	Map<String, FormData<?>> loadDataForMappings(
		Map<String, FormMapping<?>> mappings, 
		ParamsProvider paramsProvider,
		Locale locale,
		T instance,
		Class<?> ... validationGroups) {
		final Map<String, FormData<Object>> dataMap = new LinkedHashMap<String, FormData<Object>>();
		// Transformation from ? to Object (to satisfy generics)
		final Map<String, FormMapping<Object>> inputMappings = new LinkedHashMap<String, FormMapping<Object>>();
		for (Map.Entry<String, FormMapping<?>> e : mappings.entrySet()) {
			inputMappings.put(e.getKey(), (FormMapping<Object>)e.getValue());
		}
		for (Map.Entry<String, FormMapping<Object>> e : inputMappings.entrySet()) {
			Object nestedInstance = null;
			if (instance != null) {
				nestedInstance = nestedData(e.getKey(), instance); 
			}
			dataMap.put(e.getKey(), e.getValue().bind(paramsProvider, locale, nestedInstance, validationGroups));
		}
		// Transformation from Object to ? (to satisfy generics)
		final Map<String, FormData<?>> outputData = new LinkedHashMap<String, FormData<?>>();
		for (Map.Entry<String, FormData<Object>> e : dataMap.entrySet()) {
			outputData.put(e.getKey(), e.getValue());
		}
		return outputData;
	}
	
	BasicFormMappingBuilder<T> fillInternal(FormData<T> editedObj, Locale locale, RequestContext ctx) {
		Map<String, FormMapping<?>> newNestedMappings = fillNestedMappings(editedObj, locale);
		
		// Preparing values for this mapping
		Map<String, Object> propValues = gatherPropertyValues(editedObj.getData(), getAllowedProperties(fields), ctx);
				
		// Fill the definitions of fields of this mapping with prepared values
		Map<String, FormField> filledFields = fillFields(propValues, -1, locale);

		// Returning copy of this form that is filled with form data
		BasicFormMappingBuilder<T> builder = null;
		if (this.secured) {
			builder = Forms.basicSecured(getDataClass(), this.path, this.instantiator).fields(filledFields);
		} else {
			builder = Forms.basic(getDataClass(), this.path, this.instantiator).fields(filledFields);
		}
		builder.nested = newNestedMappings;
		builder.validationResult = editedObj.getValidationResult();
		builder.filledObject = editedObj.getData();
		return builder;
	}
	
	Map<String, Object> gatherPropertyValues(T editedData, Set<String> allowedProperties, RequestContext ctx) {
		Map<String, Object> propValues = new HashMap<String, Object>();
		Map<String, Object> beanValues = this.getConfig().getBeanExtractor().extractBean(editedData, allowedProperties);
		propValues.putAll(beanValues);
		putValueForAuthTokenIfSecured(propValues, ctx);
		return Collections.unmodifiableMap(propValues);
	}

	void putValueForAuthTokenIfSecured(Map<String, Object> propValues, RequestContext ctx) {
		if (isRootMapping() && this.secured) {
			if (ctx == null) {
				throw new IllegalStateException(RequestContext.class.getSimpleName() + " is required when the form is " + 
					"defined as secured. Please specify not null context in fill method.");
			}
			String genSecret = generateSecret();
			ctx.getUserRelatedStorage().set(this.formId, genSecret);
			String secret = ctx.getRequestSecret(genSecret);
			String token = this.config.getTokenAuthorizer().generateToken(secret);
			propValues.put(Forms.AUTH_TOKEN_FIELD_NAME, token);
		}
	}
	
	String generateSecret() {
		return PasswordGenerator.generatePassword(20, ALLOWED_TOKEN_CHARS);
	}
	
	boolean isRootMapping() {
		return !this.path.contains(Forms.PATH_SEP);
	}

	Map<String, FormField> fillFields(Map<String, Object> propValues, int indexInList, Locale locale) {
		Map<String, FormField> filledFields = new LinkedHashMap<String, FormField>();
		// For each field from form definition, let's fill this field with value -> filled form field
		for (Map.Entry<String, FormField> fieldDefEntry : this.fields.entrySet()) {
			final String propertyName = fieldDefEntry.getKey();
			if (indexInList >= 0 && Forms.AUTH_TOKEN_FIELD_NAME.equals(propertyName)) {
				if (isRootMapping() && this.secured) {
					throw new UnsupportedOperationException("Verification of authorization token is not supported "
						+ "in root list mapping. Please create SINGLE root mapping with nested list mapping.");
				}
			}
			
			final FormField field = fieldDefEntry.getValue();
			Object value = propValues.get(propertyName);
			String fieldName = field.getName();
			if (indexInList >= 0) {
				fieldName = nameWithIndex(field.getName(), indexInList);
			}
			final FormField filledField = FormFieldImpl.getFilledInstance(
				fieldName, field.getType(), field.getPattern(), field.getFormatter(), field.isRequired(),
				fieldValues(value), locale, this.getConfig().getFormatters());
			filledFields.put(propertyName, filledField);
		}
		filledFields = Collections.unmodifiableMap(filledFields);
		return filledFields;
	}

	Map<String, FormMapping<?>> fillNestedMappings(FormData<T> editedObj, Locale locale) {
		Map<String, FormMapping<?>> newNestedMappings = new LinkedHashMap<String, FormMapping<?>>();
		// For each definition of nested mapping, fill this mapping with edited data -> filled mapping
		for (Map.Entry<String, FormMapping<?>> e : this.nested.entrySet()) {
			// nested data - nested object or list of nested objects in case of mapping to list
			Object data = nestedData(e.getKey(), editedObj.getData());
			FormData formData = new FormData<Object>(data, editedObj.getValidationResult()); // the outer report is propagated to nested
			FormMapping newMapping = e.getValue();
			newNestedMappings.put(e.getKey(), newMapping.fill(formData, locale));
		}
		return newNestedMappings;
	}

	Set<String> getAllowedProperties(Map<String, FormField> fields) {
		Set<String> props = new LinkedHashSet<String>();
		for (FormField field : fields.values()) {
			// name of field is a full path, already prefixed with form name
			String propName = FormUtils.fieldNameToPropertyName(field.getName());
			props.add(propName);
		}
		return props;
	}
	
	String nameWithIndex(String name, int indexInList) {
		if (name == null) return null;
		String retName = name;
		int li = retName.lastIndexOf(Forms.PATH_SEP);
		if (li >= 0) {
			retName = retName.substring(0, li) + "[" + indexInList + "]" + retName.substring(li);
		}
		return retName;
	}
	
	String propertyName() {
		String pname = null;
		int li = this.path.lastIndexOf(Forms.PATH_SEP);
		if (li >= 0) {
			pname = this.path.substring(li);
		} else {
			pname = this.path;
		}
		return pname;
	}
	
	<U> U nestedData(String propName, T data) {
		Map<String, Object> props = this.getConfig().getBeanExtractor().extractBean(data, Collections.singleton(propName));
		return (U)props.get(propName); // can be null if nested object is not required
	}
	
	private <U> List<U> flatten(Collection<List<U>> collOfLists) {
		List<U> res = new ArrayList<U>();
		for (List<U> l : collOfLists) {
			res.addAll(l);
		}
		return res;
	}
	
	private List<Object> fieldValues(Object value) {
		List<Object> values = new ArrayList<Object>();
		if (value instanceof Iterable) {
			for (Object v : ((Iterable<?>)value)) {
				values.add(v);
			}
		} else if (value != null && value.getClass().isArray()) {
			values.addAll(ArrayUtils.convertPrimitiveArrayToList(value));
	    } else {
			values.add(value);
		}
		return values;
	}

	private Map<String, BoundValuesInfo> prepareValuesToBindForFields(ParamsProvider paramsProvider, Locale locale) {
		Map<String, BoundValuesInfo> values = new HashMap<String, BoundValuesInfo>();
		// Get values for each defined field
		for (Map.Entry<String, FormField> e : fields.entrySet()) {
			FormField field = e.getValue();
			String formPrefixedName = field.getName(); // already prefixed with form name
			if (!formPrefixedName.startsWith(this.path + Forms.PATH_SEP)) {
				throw new IllegalStateException("Field name '"
						+ formPrefixedName + "' not prefixed with path '"
						+ this.path + "'");
			}
			
			Object[] paramValues = null;
			UploadedFile[] files = paramsProvider.getUploadedFiles(formPrefixedName);
			if (files == null || files.length == 0) { 
				files = paramsProvider.getUploadedFiles(formPrefixedName + "[]");
			}
			if (files != null && files.length > 0) {
				// non-empty files array returned
				paramValues = files;
			} else {
				String[] strValues = paramsProvider.getParamValues(formPrefixedName);
				if (strValues == null) strValues = paramsProvider.getParamValues(formPrefixedName + "[]");
				if (this.getConfig().isInputTrimmed()) {
					strValues = trimValues(strValues);
				}
				paramValues = strValues;
			}
			String propertyName = e.getKey();
			values.put(propertyName, BoundValuesInfo.getInstance(
			  paramValues, field.getPattern(), field.getFormatter(), locale));
		}
		return values;
	}

	private String[] trimValues(String[] strValues) {
		if (strValues == null) return null;
		String[] trimmed = new String[strValues.length];
		for (int i = 0; i < strValues.length; i++) {
			trimmed[i] = strValues[i] != null ? strValues[i].trim() : null;
		}
		return trimmed;
	}
	
	private Map<String, Set<ConstraintViolationMessage>> cloneFieldMessages(Map<String, Set<ConstraintViolationMessage>> fieldMsgs) {
	  Map<String, Set<ConstraintViolationMessage>> fieldMsgCopy = new LinkedHashMap<String, Set<ConstraintViolationMessage>>();
	  for (Map.Entry<String, Set<ConstraintViolationMessage>> entry : fieldMsgs.entrySet()) {
		  fieldMsgCopy.put(entry.getKey(), new LinkedHashSet<ConstraintViolationMessage>(entry.getValue()));	
	  }
	  return fieldMsgCopy;
	}
	
	/**
	 * Returns copy of form fields that are updated with static information from configuration
	 * (like required flags). 
	 * @param sourceFields
	 * @param cfg
	 * @return
	 */
	private Map<String, FormField> configuredFields(Map<String, FormField> sourceFields, Config cfg) {
		if (cfg == null) throw new IllegalArgumentException("cfg cannot be null");
		if (this.getDataClass() == null) throw new IllegalStateException("data class cannot be null");
		
		Map<String, FormField> fields = new LinkedHashMap<String, FormField>();
		if (sourceFields != null) {
			for (Map.Entry<String, FormField> e : sourceFields.entrySet()) {
				FormField f = new FormFieldImpl(e.getValue(), cfg.getBeanValidator().isRequired(this.getDataClass(), e.getKey()));
				fields.put(e.getKey(), f);
			}
		}
		return Collections.unmodifiableMap(fields);
	}
	
	private Locale getDefaultLocale() {
		return Locale.getDefault();
	}

}
