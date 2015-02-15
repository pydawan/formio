/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.formio.render;

import net.formio.BasicListFormMapping;
import net.formio.FormElement;
import net.formio.FormField;
import net.formio.FormMapping;
import net.formio.Forms;
import net.formio.common.MessageTranslator;

/**
 * Renders labels of mappings or form fields.
 * @author Radek Beran
 */
class LabelRenderer {
	private final RenderContext ctx;

	LabelRenderer(RenderContext ctx) {
		if (ctx == null) {
			throw new IllegalArgumentException("ctx cannot be null");
		}
		this.ctx = ctx;
	}
	
	protected <T> String renderMappingLabelElement(FormMapping<T> mapping) {
		StringBuilder sb = new StringBuilder("");
		if (!mapping.isRootMapping()) {
			sb.append("<div class=\"" + getRenderContext().getFormBoxClass() + "\">" + newLine());
			sb.append("<div class=\"" + getRenderContext().getLabelIndentClass() + "\">" + newLine());
			sb.append(renderHtmlLabel(mapping));
			sb.append("</div>" + newLine());
			sb.append("</div>" + newLine());
		}
		return sb.toString();
	}

	protected <T> String renderHtmlLabel(FormElement element) {
		StringBuilder sb = new StringBuilder();
		if (element instanceof FormField) {
			sb.append("<label for=\"id-" + element.getName() + "\" class=\"" + getRenderContext().getLabelIndentClass() + "\">");
		} else {
			sb.append(renderLabelBeginTag(element));
		}
		sb.append(renderLabelText(element));
		sb.append(":");
		sb.append(renderLabelEndTag(element));
		return sb.toString();
	}
	
	protected <T> String renderLabelBeginTag(@SuppressWarnings("unused") FormElement formElement) {
		return "<label>" + newLine();
	}

	protected <T> String renderLabelEndTag(@SuppressWarnings("unused") FormElement formElement) {
		return "</label>" + newLine();
	}

	protected <T> String renderLabelText(FormElement formElement) {
		StringBuilder sb = new StringBuilder();
		MessageTranslator tr = getRenderContext().createMessageTranslator(formElement);
		String msgKey = formElement.getLabelKey();
		if (formElement instanceof FormMapping) {
			FormMapping<?> m = (FormMapping<?>) formElement;
			if (m.getIndex() != null) {
				msgKey = msgKey + Forms.PATH_SEP + "singular";
			}
		}
		sb.append(getRenderContext().escapeHtml(tr.getMessage(msgKey, getRenderContext().getLocale())));
		if (formElement instanceof BasicListFormMapping) {
			FormMapping<?> listMapping = (FormMapping<?>) formElement;
			sb.append(" (" + listMapping.getList().size() + ")");
		}
		if (formElement.isRequired()) {
			sb.append(renderRequiredMark(formElement));
		}
		return sb.toString();
	}

	protected <T> String renderRequiredMark(@SuppressWarnings("unused") FormElement formElement) {
		return "&nbsp;*";
	}
	
	protected RenderContext getRenderContext() {
		return ctx;
	}
	
	private String newLine() {
		return getRenderContext().newLine();
	}
}