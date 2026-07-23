package com.example.iml.orchestrator.integration.plc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Subject (Observable) паттерна Observer для событий FINS/DO→DI трафика.
 * Потокобезопасен: уведомление идёт по snapshot списка observers.
 */
public final class PlcFinsTrafficSubject {

  private static final Logger log = LogManager.getLogger(PlcFinsTrafficSubject.class);

  private final CopyOnWriteArrayList<PlcFinsTrafficListener> observers = new CopyOnWriteArrayList<>();

  public void addObserver(PlcFinsTrafficListener observer) {
    Objects.requireNonNull(observer, "observer");
    observers.addIfAbsent(observer);
  }

  public void removeObserver(PlcFinsTrafficListener observer) {
    if (observer != null) {
      observers.remove(observer);
    }
  }

  public void clearObservers() {
    observers.clear();
  }

  /**
   * Совместимость со старым {@code setTrafficListener}: один observer (или ни одного).
   */
  public void setObserver(PlcFinsTrafficListener observer) {
    observers.clear();
    if (observer != null) {
      observers.add(observer);
    }
  }

  public int observerCount() {
    return observers.size();
  }

  public List<PlcFinsTrafficListener> observers() {
    return List.copyOf(observers);
  }

  public void notifyObservers(PlcFinsTrafficEvent event) {
    if (event == null || observers.isEmpty()) {
      return;
    }
    for (PlcFinsTrafficListener observer : observers) {
      try {
        observer.onTraffic(event);
      } catch (Exception e) {
        log.debug("plc fins traffic observer error: {}", e.getMessage());
      }
    }
  }
}
