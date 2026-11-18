package com.unisono.card.limits.infrastructure.adapter.output.policy;

import com.unisono.card.limits.domain.model.LimitPolicy;
import com.unisono.card.limits.infrastructure.config.LimitPolicyProperties;
import com.unisono.card.shared.domain.exception.BusinessRuleException;
import com.unisono.card.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * card.limits.policies de application.yml se carga como LimitPolicy, y una configuración inválida no arranca.
 */
class ConfiguredLimitPolicyAdapterTest {

    @Test
    void loadsTheArsPolicyFromApplicationYml() throws IOException {
        ConfiguredLimitPolicyAdapter adapter = new ConfiguredLimitPolicyAdapter(bindApplicationYml());

        LimitPolicy ars = adapter.forCurrency("ARS");

        assertThat(ars.minPurchaseLimit()).isEqualTo(Money.ofARS(2_500_000));
        assertThat(ars.maxPurchaseLimit()).isEqualTo(Money.ofARS(25_000_000));
        assertThat(ars.minCashAdvanceLimit()).isEqualTo(Money.ofARS(500));
        assertThat(ars.maxCashAdvanceLimit()).isEqualTo(Money.ofARS(7_500_000));
        assertThat(ars.defaultDailyLimit()).isEqualTo(Money.ofARS(1_250_000));
        assertThat(ars.defaultMonthlyLimit()).isEqualTo(Money.ofARS(1_250_000));
    }

    @Test
    void aCurrencyWithoutPolicyIsABusinessRuleViolation() throws IOException {
        ConfiguredLimitPolicyAdapter adapter = new ConfiguredLimitPolicyAdapter(bindApplicationYml());

        assertThatThrownBy(() -> adapter.forCurrency("USD"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("USD");
    }

    @Test
    void failsAtStartupWithoutPolicies() {
        assertThatThrownBy(() -> new ConfiguredLimitPolicyAdapter(new LimitPolicyProperties(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("card.limits.policies");
    }

    @Test
    void failsAtStartupWithAMissingValue() {
        LimitPolicyProperties properties = new LimitPolicyProperties(Map.of("USD", new LimitPolicyProperties.Policy(
                bd(1_000), bd(10_000), bd(100), null, bd(1_000), bd(5_000))));

        assertThatThrownBy(() -> new ConfiguredLimitPolicyAdapter(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("card.limits.policies.USD")
                .hasMessageContaining("max-cash-advance-limit");
    }

    @Test
    void failsAtStartupWithAnInconsistentPolicy() {
        LimitPolicyProperties properties = new LimitPolicyProperties(Map.of("USD", new LimitPolicyProperties.Policy(
                bd(10_000), bd(1_000), bd(100), bd(500), bd(1_000), bd(5_000))));

        assertThatThrownBy(() -> new ConfiguredLimitPolicyAdapter(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("card.limits.policies.USD");
    }

    @Test
    void failsAtStartupWhenTheDefaultsDoNotFitTheRanges() {
        // 30% de 1.000 = 300, menor al mínimo de adelantos de 500
        LimitPolicyProperties properties = new LimitPolicyProperties(Map.of("USD", new LimitPolicyProperties.Policy(
                bd(1_000), bd(10_000), bd(500), bd(3_000), bd(500), bd(1_000))));

        assertThatThrownBy(() -> new ConfiguredLimitPolicyAdapter(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("card.limits.policies.USD")
                .hasMessageContaining("menor al mínimo de adelantos");
    }

    @Test
    void failsAtStartupWithAnUnknownCurrency() {
        LimitPolicyProperties properties = new LimitPolicyProperties(Map.of("XXY", new LimitPolicyProperties.Policy(
                bd(1_000), bd(10_000), bd(100), bd(500), bd(1_000), bd(5_000))));

        assertThatThrownBy(() -> new ConfiguredLimitPolicyAdapter(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("XXY");
    }

    private static LimitPolicyProperties bindApplicationYml() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return new Binder(ConfigurationPropertySources.get(environment))
                .bind("card.limits", LimitPolicyProperties.class)
                .get();
    }

    private static BigDecimal bd(long amount) {
        return BigDecimal.valueOf(amount);
    }
}
