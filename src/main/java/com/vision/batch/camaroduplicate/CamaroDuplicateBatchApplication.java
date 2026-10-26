package com.vision.batch.camaroduplicate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone entry point, only for running the template in isolation.
 * In Vision: delete this class; the existing application picks up
 * CamaroDuplicateJobConfig and CamaroDuplicateJobRunner by component scan.
 */
@SpringBootApplication
public class CamaroDuplicateBatchApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(CamaroDuplicateBatchApplication.class, args)));
    }
}
