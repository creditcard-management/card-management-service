package com.unisono.card.data.infrastructure.adapter.input.scheduler;

import com.unisono.card.data.domain.port.input.ExpireCardsUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Spring tiene que poder crear el scheduler: tiene dos constructores (el de la configuración y uno con
 * {@code Clock} para tests). Sin {@code @Autowired} en el público, el contexto de card-data no arranca
 * ("No default constructor found") y el servicio se reinicia en loop.
 */
class CardExpirationSchedulerWiringTest {

    @Test
    void springCreatesTheSchedulerWithTheConfiguredConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ExpireCardsUseCase.class, () -> mock(ExpireCardsUseCase.class));
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.registerBean(PropertySourcesPlaceholderConfigurer.class);
            context.register(CardExpirationScheduler.class);

            context.refresh();

            assertThat(context.getBean(CardExpirationScheduler.class)).isNotNull();
            assertThat(context.getBean(MeterRegistry.class).find("card.data.cards.expired").counter()).isNotNull();
        }
    }
}
