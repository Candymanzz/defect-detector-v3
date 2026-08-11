# Проверка обучения на разных формах и размерах

- Обучено семейств: **12/12**.
- Допустимые варианты: **72/72** распознаны как ГОДЕН.
- Новые/увеличенные формы: **61/144** сохранены как БРАК.
- Ложных БРАК: **0**.
- Опасных ложных ГОДЕН: **83**.

## Обучение

| Семейство | Seed | Score | Дефектов в review | Сохранено |
|---|---|---:|---:|---|
| `circle` | БРАК | 1.0000 | 1 | да |
| `ellipse` | БРАК | 1.0000 | 1 | да |
| `rectangle_chip` | БРАК | 1.0000 | 1 | да |
| `triangle_chip` | БРАК | 1.0000 | 1 | да |
| `irregular_blob` | БРАК | 1.0000 | 1 | да |
| `horizontal_scratch` | БРАК | 1.0000 | 1 | да |
| `vertical_scratch` | БРАК | 1.0000 | 1 | да |
| `diagonal_scratch` | БРАК | 1.0000 | 1 | да |
| `zigzag_crack` | БРАК | 1.0000 | 1 | да |
| `arc` | БРАК | 1.0000 | 1 | да |
| `x_scratch` | БРАК | 1.0000 | 1 | да |
| `dot_cluster` | БРАК | 1.0000 | 1 | да |

## Ошибки

| Сохранённая норма | Проверяемая форма | Вариант | Ожидание | Факт | Similarity |
|---|---|---|---|---|---:|
| `circle` | `ellipse` | `foreign_shape` | БРАК | ГОДЕН | 0.9316 |
| `circle` | `rectangle_chip` | `foreign_shape` | БРАК | ГОДЕН | 0.9433 |
| `circle` | `horizontal_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.9359 |
| `circle` | `vertical_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.9358 |
| `circle` | `dot_cluster` | `foreign_shape` | БРАК | ГОДЕН | 0.8813 |
| `ellipse` | `circle` | `foreign_shape` | БРАК | ГОДЕН | 0.9494 |
| `ellipse` | `rectangle_chip` | `foreign_shape` | БРАК | ГОДЕН | 0.9290 |
| `ellipse` | `triangle_chip` | `foreign_shape` | БРАК | ГОДЕН | 0.9027 |
| `ellipse` | `horizontal_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.9284 |
| `ellipse` | `vertical_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.9144 |
| `ellipse` | `dot_cluster` | `foreign_shape` | БРАК | ГОДЕН | 0.8751 |
| `rectangle_chip` | `circle` | `foreign_shape` | БРАК | ГОДЕН | 0.9547 |
| `rectangle_chip` | `ellipse` | `foreign_shape` | БРАК | ГОДЕН | 0.9183 |
| `rectangle_chip` | `triangle_chip` | `foreign_shape` | БРАК | ГОДЕН | 0.9037 |
| `rectangle_chip` | `horizontal_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.9463 |
| `rectangle_chip` | `vertical_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.9179 |
| `rectangle_chip` | `dot_cluster` | `foreign_shape` | БРАК | ГОДЕН | 0.8877 |
| `triangle_chip` | `circle` | `foreign_shape` | БРАК | ГОДЕН | 0.9316 |
| `triangle_chip` | `ellipse` | `foreign_shape` | БРАК | ГОДЕН | 0.9264 |
| `triangle_chip` | `rectangle_chip` | `foreign_shape` | БРАК | ГОДЕН | 0.9230 |
| `triangle_chip` | `irregular_blob` | `foreign_shape` | БРАК | ГОДЕН | 0.9040 |
| `triangle_chip` | `horizontal_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.9100 |
| `triangle_chip` | `vertical_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.9208 |
| `triangle_chip` | `arc` | `foreign_shape` | БРАК | ГОДЕН | 0.8384 |
| `triangle_chip` | `dot_cluster` | `foreign_shape` | БРАК | ГОДЕН | 0.8659 |
| `irregular_blob` | `circle` | `foreign_shape` | БРАК | ГОДЕН | 0.9651 |
| `irregular_blob` | `ellipse` | `foreign_shape` | БРАК | ГОДЕН | 0.9494 |
| `irregular_blob` | `rectangle_chip` | `foreign_shape` | БРАК | ГОДЕН | 0.9496 |
| `irregular_blob` | `triangle_chip` | `foreign_shape` | БРАК | ГОДЕН | 0.9072 |
| `irregular_blob` | `horizontal_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.9313 |
| `irregular_blob` | `vertical_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.9327 |
| `irregular_blob` | `arc` | `foreign_shape` | БРАК | ГОДЕН | 0.8616 |
| `irregular_blob` | `x_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.8546 |
| `irregular_blob` | `dot_cluster` | `foreign_shape` | БРАК | ГОДЕН | 0.9049 |
| `horizontal_scratch` | `circle` | `foreign_shape` | БРАК | ГОДЕН | 0.9364 |
| `horizontal_scratch` | `ellipse` | `foreign_shape` | БРАК | ГОДЕН | 0.9107 |
| `horizontal_scratch` | `rectangle_chip` | `foreign_shape` | БРАК | ГОДЕН | 0.9427 |
| `horizontal_scratch` | `vertical_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.9074 |
| `vertical_scratch` | `circle` | `foreign_shape` | БРАК | ГОДЕН | 0.9177 |
| `vertical_scratch` | `ellipse` | `foreign_shape` | БРАК | ГОДЕН | 0.8904 |
| `vertical_scratch` | `rectangle_chip` | `foreign_shape` | БРАК | ГОДЕН | 0.8948 |
| `vertical_scratch` | `horizontal_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.8962 |
| `vertical_scratch` | `dot_cluster` | `foreign_shape` | БРАК | ГОДЕН | 0.8775 |
| `diagonal_scratch` | `diagonal_scratch` | `large_145` | БРАК | ГОДЕН | 0.9499 |
| `diagonal_scratch` | `rectangle_chip` | `foreign_shape` | БРАК | ГОДЕН | 0.8120 |
| `diagonal_scratch` | `horizontal_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.7994 |
| `diagonal_scratch` | `vertical_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.7986 |
| `diagonal_scratch` | `zigzag_crack` | `foreign_shape` | БРАК | ГОДЕН | 0.8824 |
| `diagonal_scratch` | `x_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.8474 |
| `diagonal_scratch` | `dot_cluster` | `foreign_shape` | БРАК | ГОДЕН | 0.8258 |
| `zigzag_crack` | `circle` | `foreign_shape` | БРАК | ГОДЕН | 0.8080 |
| `zigzag_crack` | `rectangle_chip` | `foreign_shape` | БРАК | ГОДЕН | 0.8358 |
| `zigzag_crack` | `triangle_chip` | `foreign_shape` | БРАК | ГОДЕН | 0.8271 |
| `zigzag_crack` | `irregular_blob` | `foreign_shape` | БРАК | ГОДЕН | 0.7938 |
| `zigzag_crack` | `horizontal_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.8259 |
| `zigzag_crack` | `vertical_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.8242 |
| `zigzag_crack` | `diagonal_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.8735 |
| `zigzag_crack` | `arc` | `foreign_shape` | БРАК | ГОДЕН | 0.8273 |
| `zigzag_crack` | `x_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.8130 |
| `zigzag_crack` | `dot_cluster` | `foreign_shape` | БРАК | ГОДЕН | 0.8352 |
| `arc` | `circle` | `foreign_shape` | БРАК | ГОДЕН | 0.8491 |
| `arc` | `rectangle_chip` | `foreign_shape` | БРАК | ГОДЕН | 0.8447 |
| `arc` | `triangle_chip` | `foreign_shape` | БРАК | ГОДЕН | 0.8335 |
| `arc` | `vertical_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.8480 |
| `arc` | `x_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.7862 |
| `arc` | `dot_cluster` | `foreign_shape` | БРАК | ГОДЕН | 0.8157 |
| `x_scratch` | `circle` | `foreign_shape` | БРАК | ГОДЕН | 0.8524 |
| `x_scratch` | `ellipse` | `foreign_shape` | БРАК | ГОДЕН | 0.8568 |
| `x_scratch` | `rectangle_chip` | `foreign_shape` | БРАК | ГОДЕН | 0.8717 |
| `x_scratch` | `triangle_chip` | `foreign_shape` | БРАК | ГОДЕН | 0.8796 |
| `x_scratch` | `irregular_blob` | `foreign_shape` | БРАК | ГОДЕН | 0.8581 |
| `x_scratch` | `horizontal_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.8742 |
| `x_scratch` | `vertical_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.8702 |
| `x_scratch` | `diagonal_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.8390 |
| `x_scratch` | `zigzag_crack` | `foreign_shape` | БРАК | ГОДЕН | 0.8163 |
| `x_scratch` | `arc` | `foreign_shape` | БРАК | ГОДЕН | 0.8111 |
| `x_scratch` | `dot_cluster` | `foreign_shape` | БРАК | ГОДЕН | 0.8645 |
| `dot_cluster` | `circle` | `foreign_shape` | БРАК | ГОДЕН | 0.8861 |
| `dot_cluster` | `ellipse` | `foreign_shape` | БРАК | ГОДЕН | 0.8778 |
| `dot_cluster` | `rectangle_chip` | `foreign_shape` | БРАК | ГОДЕН | 0.8795 |
| `dot_cluster` | `triangle_chip` | `foreign_shape` | БРАК | ГОДЕН | 0.8654 |
| `dot_cluster` | `horizontal_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.8845 |
| `dot_cluster` | `vertical_scratch` | `foreign_shape` | БРАК | ГОДЕН | 0.8829 |
