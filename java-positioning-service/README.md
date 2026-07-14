# java-positioning-service

Нормализует позу изделия относительно эталона (ORB + homography), пишет выровненный BGR-кадр в SHM
и отдаёт его дальше в `java-geometry-service` и `analisSurface`.

Большая разбежка положений между кадрами — ожидаема: сервис всегда приводит изделие к позе эталона.
`PASS` = гомография найдена и кадр записан (величина сдвига/поворота не отбраковывает).

## Протокол (stdio IMLB, как geometry)

| Op | Назначение |
|----|------------|
| `health` | `{status: ok, service: java-positioning-service}` |
| `position_shm` | выровнять current→reference и записать output SHM |

### Вход `position_shm`

Те же поля SHM, что у geometry `inspect_shm`, плюс:

- `output_shm_name` — куда писать выровненный кадр (по умолчанию `iml_pos_cam_{id}`)
- `mainRoi` / `mainRoiPolygonNorm`
- `pixelsToMm`, `maxShiftMm`, `maxRotationDeg`
- `write_aligned` (default `true`)

### Ответ

`shiftXmm`, `shiftYmm`, `rotationDeg`, `homographyRefToCurrent`, `alignmentPass`, `overallPass`, `alignedWritten`, размеры и имя output SHM.

## Сборка

```bash
mvn -f java-positioning-service/pom.xml -DskipTests package
```

Оркестратор поднимает пул процессов по `integration.positioning_command_*` и вставляет этап **capture → position → geometry → python**.
