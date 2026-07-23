package com.example.iml.orchestrator.integration.plc;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlcFinsTrafficSubjectTest {

  @Test
  void notifiesAllObservers() {
    PlcFinsTrafficSubject subject = new PlcFinsTrafficSubject();
    List<String> seen = new ArrayList<>();
    subject.addObserver(e -> seen.add("a:" + e.operation()));
    subject.addObserver(e -> seen.add("b:" + e.operation()));

    subject.notifyObservers(new PlcFinsTrafficEvent(
        PlcFinsTrafficEvent.DIRECTION_REQUEST,
        PlcFinsTrafficEvent.OP_WRITE_BIT,
        "reject_line_1",
        "CIO",
        "100.0",
        true,
        "AABB",
        1,
        null,
        true,
        null,
        1L
    ));

    assertEquals(List.of("a:write_bit", "b:write_bit"), seen);
    assertEquals(2, subject.observerCount());
  }

  @Test
  void setObserverReplacesAll() {
    PlcFinsTrafficSubject subject = new PlcFinsTrafficSubject();
    AtomicInteger first = new AtomicInteger();
    AtomicInteger second = new AtomicInteger();
    subject.addObserver(e -> first.incrementAndGet());
    subject.setObserver(e -> second.incrementAndGet());

    subject.notifyObservers(dummy());

    assertEquals(0, first.get());
    assertEquals(1, second.get());
    assertEquals(1, subject.observerCount());
  }

  @Test
  void observerExceptionDoesNotBlockOthers() {
    PlcFinsTrafficSubject subject = new PlcFinsTrafficSubject();
    AtomicInteger ok = new AtomicInteger();
    subject.addObserver(e -> {
      throw new RuntimeException("boom");
    });
    subject.addObserver(e -> ok.incrementAndGet());

    subject.notifyObservers(dummy());

    assertEquals(1, ok.get());
  }

  @Test
  void removeObserver() {
    PlcFinsTrafficSubject subject = new PlcFinsTrafficSubject();
    AtomicInteger count = new AtomicInteger();
    PlcFinsTrafficListener observer = e -> count.incrementAndGet();
    subject.addObserver(observer);
    subject.removeObserver(observer);
    subject.notifyObservers(dummy());
    assertEquals(0, count.get());
    assertTrue(subject.observers().isEmpty());
  }

  private static PlcFinsTrafficEvent dummy() {
    return new PlcFinsTrafficEvent(
        PlcFinsTrafficEvent.DIRECTION_RESPONSE,
        PlcFinsTrafficEvent.OP_READ_WORDS,
        "t",
        "DM",
        "D0",
        1,
        "",
        2,
        "0000",
        true,
        null,
        2L
    );
  }
}
