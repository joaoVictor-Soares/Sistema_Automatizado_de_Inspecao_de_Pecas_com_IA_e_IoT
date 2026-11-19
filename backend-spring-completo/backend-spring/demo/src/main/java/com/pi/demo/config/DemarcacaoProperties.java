package com.pi.demo.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Regiao da imagem (demarcacao) onde a tampinha e detectada. Valores em fracao da imagem (0 a 1).
 * Calibrar na mesa: a regiao deve cobrir o ponto por onde a tampa passa.
 */
@ConfigurationProperties(prefix = "inspecao.demarcacao")
public record DemarcacaoProperties(
        @DefaultValue("0.40") double x,
        @DefaultValue("0.40") double y,
        @DefaultValue("0.20") double largura,
        @DefaultValue("0.20") double altura,
        /** fracao de pixels verdes dentro da regiao que caracteriza a tampa presente */
        @DefaultValue("0.08") double limiarVerde,
        /** intervalo entre frames do monitoramento */
        @DefaultValue("PT0.3S") Duration intervalo,
        /** tempo minimo entre duas inspecoes da mesma tampa */
        @DefaultValue("PT2S") Duration cooldown) {
}
