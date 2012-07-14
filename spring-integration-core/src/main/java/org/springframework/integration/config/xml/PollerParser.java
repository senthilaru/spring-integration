/*
 * Copyright 2002-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.config.xml;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.AbstractBeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.integration.channel.MessagePublishingErrorHandler;
import org.springframework.integration.config.ExpressionFactoryBean;
import org.springframework.integration.context.IntegrationContextUtils;
import org.springframework.integration.scheduling.PollerMetadata;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.util.StringUtils;
import org.springframework.util.xml.DomUtils;
import org.w3c.dom.Element;

/**
 * Parser for the &lt;poller&gt; element.
 *
 * @author Mark Fisher
 * @author Marius Bogoevici
 * @author Oleg Zhurakousky
 * @author Artem Bilan
 * @author Gary Russell
 */
public class PollerParser extends AbstractBeanDefinitionParser {

	private static final String MULTIPLE_TRIGGER_DEFINITIONS = "A <poller> cannot specify more than one trigger configuration.";

	private static final String NO_TRIGGER_DEFINITIONS = "A <poller> must have one and only one trigger configuration.";

	@Override
	protected String resolveId(Element element, AbstractBeanDefinition definition, ParserContext parserContext) throws BeanDefinitionStoreException {
		String id = super.resolveId(element, definition, parserContext);
		if (element.getAttribute("default").equals("true")) {
			if (parserContext.getRegistry().isBeanNameInUse(IntegrationContextUtils.DEFAULT_POLLER_METADATA_BEAN_NAME)) {
				parserContext.getReaderContext().error(
						"Only one default <poller/> element is allowed per context.", element);
			}
			if (StringUtils.hasText(id)) {
				parserContext.getRegistry().registerAlias(id, IntegrationContextUtils.DEFAULT_POLLER_METADATA_BEAN_NAME);
			}
			else {
				id = IntegrationContextUtils.DEFAULT_POLLER_METADATA_BEAN_NAME;
			}
		}
		else if (!StringUtils.hasText(id)) {
			parserContext.getReaderContext().error(
					"The 'id' attribute is required for a top-level poller element unless it is the default poller.",
					element);
		}
		return id;
	}

	@Override
	protected AbstractBeanDefinition parseInternal(Element element, ParserContext parserContext) {
		BeanDefinitionBuilder metadataBuilder = BeanDefinitionBuilder.genericBeanDefinition(PollerMetadata.class);
		if (element.hasAttribute("ref")) {
			parserContext.getReaderContext().error(
					"the 'ref' attribute must not be present on the top-level 'poller' element", element);
		}
		configureTrigger(element, metadataBuilder, parserContext);
		IntegrationNamespaceUtils.setValueIfAttributeDefined(metadataBuilder, element, "max-messages-per-poll");
		IntegrationNamespaceUtils.setValueIfAttributeDefined(metadataBuilder, element, "receive-timeout");

		Element txElement = DomUtils.getChildElementByTagName(element, "transactional");
		Element adviceChainElement = DomUtils.getChildElementByTagName(element, "advice-chain");
		IntegrationNamespaceUtils.configureAndSetAdviceChainIfPresent(adviceChainElement, txElement,
				metadataBuilder, parserContext);

		Element pseudoTxElement = DomUtils.getChildElementByTagName(element, "psuedo-transactional");
		if (pseudoTxElement != null && txElement != null) {
			parserContext.getReaderContext().error(
					"Cannot have both 'transactional' and 'pseudo-transactional' elements", element);
		}
		Element txSyncElement = DomUtils.getChildElementByTagName(element, "transaction-synchronization");
		if (pseudoTxElement != null && txSyncElement != null) {
			parserContext.getReaderContext().error(
					"Cannot have both 'transaction-synchronization' and 'pseudo-transactional' elements", element);
		}
		pseudoTxElement = pseudoTxElement == null ? txSyncElement : pseudoTxElement;
		configureTransactionSync(pseudoTxElement, metadataBuilder, parserContext);

		IntegrationNamespaceUtils.setReferenceIfAttributeDefined(metadataBuilder, element, "task-executor");
		String errorChannel = element.getAttribute("error-channel");
		if (StringUtils.hasText(errorChannel)) {
			BeanDefinitionBuilder errorHandler = BeanDefinitionBuilder.genericBeanDefinition(MessagePublishingErrorHandler.class);
			errorHandler.addPropertyReference("defaultErrorChannel", errorChannel);
			metadataBuilder.addPropertyValue("errorHandler", errorHandler.getBeanDefinition());
		}
		return metadataBuilder.getBeanDefinition();
	}

	private void configureTrigger(Element pollerElement, BeanDefinitionBuilder targetBuilder, ParserContext parserContext) {
		String triggerAttribute = pollerElement.getAttribute("trigger");
		String fixedRateAttribute = pollerElement.getAttribute("fixed-rate");
		String fixedDelayAttribute = pollerElement.getAttribute("fixed-delay");
		String cronAttribute = pollerElement.getAttribute("cron");
		String timeUnit = pollerElement.getAttribute("time-unit");

		List<String> triggerBeanNames = new ArrayList<String>();
		if (StringUtils.hasText(triggerAttribute)) {
			if (StringUtils.hasText(timeUnit)) {
				parserContext.getReaderContext().error("The 'time-unit' attribute cannot be used with a 'trigger' reference.", pollerElement);
			}
			triggerBeanNames.add(triggerAttribute);
		}
		if (StringUtils.hasText(fixedRateAttribute)) {
			BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(PeriodicTrigger.class);
			builder.addConstructorArgValue(fixedRateAttribute);
			if (StringUtils.hasText(timeUnit)) {
				builder.addConstructorArgValue(timeUnit);
			}
			builder.addPropertyValue("fixedRate", Boolean.TRUE);
			String triggerBeanName = BeanDefinitionReaderUtils.registerWithGeneratedName(
					builder.getBeanDefinition(), parserContext.getRegistry());
			triggerBeanNames.add(triggerBeanName);
		}
		if (StringUtils.hasText(fixedDelayAttribute)) {
			BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(PeriodicTrigger.class);
			builder.addConstructorArgValue(fixedDelayAttribute);
			if (StringUtils.hasText(timeUnit)) {
				builder.addConstructorArgValue(timeUnit);
			}
			builder.addPropertyValue("fixedRate", Boolean.FALSE);
			String triggerBeanName = BeanDefinitionReaderUtils.registerWithGeneratedName(
					builder.getBeanDefinition(), parserContext.getRegistry());
			triggerBeanNames.add(triggerBeanName);
		}
		if (StringUtils.hasText(cronAttribute)) {
			if (StringUtils.hasText(timeUnit)) {
				parserContext.getReaderContext().error("The 'time-unit' attribute cannot be used with a 'cron' trigger.", pollerElement);
			}
			BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(CronTrigger.class);
			builder.addConstructorArgValue(cronAttribute);
			String triggerBeanName = BeanDefinitionReaderUtils.registerWithGeneratedName(
					builder.getBeanDefinition(), parserContext.getRegistry());
			triggerBeanNames.add(triggerBeanName);
		}
		if (triggerBeanNames.isEmpty()) {
			parserContext.getReaderContext().error(NO_TRIGGER_DEFINITIONS, pollerElement);
		}
		if (triggerBeanNames.size() > 1) {
			parserContext.getReaderContext().error(MULTIPLE_TRIGGER_DEFINITIONS, pollerElement);
		}
		targetBuilder.addPropertyReference("trigger", triggerBeanNames.get(0));
	}

	private void configureTransactionSync(Element element, BeanDefinitionBuilder metadataBuilder,
			ParserContext parserContext) {
		if (element != null) {
			configureSyncExpression(element, metadataBuilder, parserContext, "on-success-expression", "onSuccessExpression");
			IntegrationNamespaceUtils.setReferenceIfAttributeDefined(metadataBuilder, element, "on-success-result-channel");
			configureSyncExpression(element, metadataBuilder, parserContext, "on-failure-expression", "onFailureExpression");
			IntegrationNamespaceUtils.setReferenceIfAttributeDefined(metadataBuilder, element, "on-failure-result-channel");
			IntegrationNamespaceUtils.setValueIfAttributeDefined(metadataBuilder, element, "send-timeout", "sendTimeout");
		}
	}

	private void configureSyncExpression(Element element, BeanDefinitionBuilder metadataBuilder,
			ParserContext parserContext, String expressionAttribute, String expressionProperty) {
		String expression = element.getAttribute(expressionAttribute);
		if (StringUtils.hasText(expression)) {
			RootBeanDefinition expressionDef = new RootBeanDefinition(ExpressionFactoryBean.class);
			expressionDef.getConstructorArgumentValues().addGenericArgumentValue(expression);
			metadataBuilder.addPropertyValue(expressionProperty, expressionDef);
		}
	}
}
