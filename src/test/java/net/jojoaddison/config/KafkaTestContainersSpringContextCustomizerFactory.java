package net.jojoaddison.config;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.testcontainers.containers.KafkaContainer;

public class KafkaTestContainersSpringContextCustomizerFactory implements ContextCustomizerFactory {

    private Logger log = LoggerFactory.getLogger(KafkaTestContainersSpringContextCustomizerFactory.class);

    private static KafkaTestContainer kafkaBean;

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        return (context, mergedConfig) -> {
            ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
            TestPropertyValues testValues = TestPropertyValues.empty();
            EmbeddedKafka kafkaAnnotation = AnnotatedElementUtils.findMergedAnnotation(testClass, EmbeddedKafka.class);
            if (null != kafkaAnnotation) {
                log.debug("detected the EmbeddedKafka annotation on class {}", testClass.getName());
                log.info("Warming up the kafka broker");
                // The container itself is shared across contexts on purpose — starting a broker per
                // context would be intolerably slow. But the REGISTRATION must happen for every
                // context: it used to sit inside this null check, so only the first Kafka-using
                // context ever got the bean and any later one failed to autowire it. That made the
                // suite order-dependent, and adding a second @EmbeddedKafka context anywhere broke
                // DomainEventsKafkaIT with a bean-not-found that pointed nowhere near the cause.
                if (null == kafkaBean) {
                    kafkaBean = beanFactory.createBean(KafkaTestContainer.class);
                }
                if (!beanFactory.containsSingleton(KafkaTestContainer.class.getName())) {
                    beanFactory.registerSingleton(KafkaTestContainer.class.getName(), kafkaBean);
                }
                testValues = testValues.and(
                    "spring.cloud.stream.kafka.binder.brokers=" +
                    kafkaBean.getKafkaContainer().getHost() +
                    ':' +
                    kafkaBean.getKafkaContainer().getMappedPort(KafkaContainer.KAFKA_PORT)
                );
            }
            testValues.applyTo(context);
        };
    }
}
