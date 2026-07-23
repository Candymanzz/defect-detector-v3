package com.example.iml.orchestrator.integration.bootstrap.lifecycle;

import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Composite жизненного цикла: старт по порядку регистрации, close в обратном.
 */
public final class IntegrationLifecycleComposite implements IntegrationComponent {

    private final Logger log;
    private final List<IntegrationComponent> children = new ArrayList<>();
    private int startedThrough = -1;

    public IntegrationLifecycleComposite(Logger log) {
        this.log = log;
    }

    public void register(IntegrationComponent component) {
        if (component != null) {
            children.add(component);
        }
    }

    public void registerAll(Iterable<? extends IntegrationComponent> components) {
        if (components == null) {
            return;
        }
        for (IntegrationComponent component : components) {
            register(component);
        }
    }

    @Override
    public void start() throws Exception {
        for (int i = 0; i < children.size(); i++) {
            children.get(i).start();
            startedThrough = i;
        }
    }

    @Override
    public void close() {
        for (int i = Math.min(startedThrough, children.size() - 1); i >= 0; i--) {
            try {
                children.get(i).close();
            } catch (Exception e) {
                log.warn("lifecycle close failed component={}: {}", children.get(i).getClass().getSimpleName(), e.getMessage());
            }
        }
        startedThrough = -1;
    }
}
