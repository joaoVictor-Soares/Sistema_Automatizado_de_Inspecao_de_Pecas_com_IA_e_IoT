package com.pi.demo.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import com.pi.demo.config.DemarcacaoProperties;
import com.pi.demo.exception.CameraException;

/**
 * Mede, dentro da regiao de demarcacao, a fracao de pixels da cor verde da tampinha.
 * Usa so a JDK (ImageIO + HSV manual), sem OpenCV, para rodar no backend sem dependencias nativas.
 */
public final class DemarcacaoDetector {

    private DemarcacaoDetector() {
    }

    /** Fracao (0 a 1) de pixels verdes na regiao de demarcacao do JPEG informado. */
    public static double fracaoVerde(byte[] jpeg, DemarcacaoProperties roi) {
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(jpeg));
        } catch (IOException e) {
            throw new CameraException("Frame invalido: " + e.getMessage(), e);
        }
        if (img == null) {
            throw new CameraException("Frame nao decodificado (formato nao suportado).");
        }
        int w = img.getWidth();
        int h = img.getHeight();
        int x0 = limitar((int) (roi.x() * w), 0, w - 1);
        int y0 = limitar((int) (roi.y() * h), 0, h - 1);
        int x1 = limitar((int) ((roi.x() + roi.largura()) * w), x0 + 1, w);
        int y1 = limitar((int) ((roi.y() + roi.altura()) * h), y0 + 1, h);

        long total = 0;
        long verdes = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int rgb = img.getRGB(x, y);
                if (ehVerde((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)) {
                    verdes++;
                }
                total++;
            }
        }
        return total == 0 ? 0.0 : (double) verdes / total;
    }

    /** Mesma faixa da captura Java (OpenCV H 35..90 = 70..180 graus; S >= 0.2; V >= 0.15). */
    static boolean ehVerde(int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max == 0) {
            return false;
        }
        double saturacao = (max - min) / (double) max;
        double valor = max / 255.0;
        if (saturacao < 0.2 || valor < 0.15) {
            return false;
        }
        double d = max - min;
        double matiz;
        if (max == r) {
            matiz = 60.0 * (((g - b) / d) % 6);
        } else if (max == g) {
            matiz = 60.0 * ((b - r) / d + 2);
        } else {
            matiz = 60.0 * ((r - g) / d + 4);
        }
        if (matiz < 0) {
            matiz += 360;
        }
        return matiz >= 70 && matiz <= 180;
    }

    private static int limitar(int valor, int min, int max) {
        return Math.max(min, Math.min(max, valor));
    }
}
