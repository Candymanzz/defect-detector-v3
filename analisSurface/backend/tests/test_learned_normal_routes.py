import cv2
import numpy as np
from fastapi.testclient import TestClient

from app.api.dependencies import inspection_service
from app.main import app


client = TestClient(app)


def test_learning_review_page_is_available() -> None:
    response = client.get("/learning-review")
    assert response.status_code == 200
    assert "Обучение допустимым фрагментам" in response.text
    assert "Дообучить этот БРАК" in response.text
    assert "Сохранённые нормы" in response.text
    assert "Удалить из списка нормы" in response.text
    assert "Считать все дефекты допустимой нормой" in response.text
    assert "СОХРАНЁН КАК НОРМА" in response.text
    assert "БУДЕТ СОХРАНЁН ПО КНОПКЕ" in response.text
    assert "/accept-all-as-normal" in response.text


def test_local_inspection_test_page_is_available() -> None:
    response = client.get("/local-inspection-test")
    assert response.status_code == 200
    assert "Локальный тест инспекции" in response.text
    assert "Дообучить этот БРАК" in response.text
    assert "Запустить проверку" in response.text
    assert "Сохранённые нормы теста" in response.text
    assert "Эталон и область инспекции" in response.text
    assert "fetch('/roi-polygon'" in response.text
    assert "fetch('/upload-ref'" in response.text
    assert "fetch('/inspect'" in response.text
    assert "Считать все дефекты нормой" in response.text
    assert "СОХРАНЁН КАК НОРМА" in response.text
    assert "БУДЕТ СОХРАНЁН ПО КНОПКЕ" in response.text
    assert "/accept-all-as-normal" in response.text


def test_accept_all_defects_route(monkeypatch) -> None:
    expected = {
        "saved": True,
        "accepted_count": 2,
        "inspection_id": "inspection-1",
    }
    monkeypatch.setattr(
        inspection_service,
        "accept_all_review_defects_as_normal",
        lambda inspection_id, note="": {
            **expected,
            "inspection_id": inspection_id,
            "note": note,
        },
    )

    response = client.post(
        "/learning/reviews/inspection-1/accept-all-as-normal",
        json={"note": "accepted image"},
    )

    assert response.status_code == 200
    assert response.json()["accepted_count"] == 2
    assert response.json()["note"] == "accepted image"


def test_local_inspection_multipart_flow_returns_visuals_and_review() -> None:
    width, height = 80, 64
    gradient = np.tile(np.linspace(25, 185, width, dtype=np.uint8), (height, 1))
    reference = cv2.cvtColor(gradient, cv2.COLOR_GRAY2BGR)
    current = reference.copy()
    current[12:32, 14:48] = 255
    ref_ok, ref_png = cv2.imencode(".png", reference)
    current_ok, current_png = cv2.imencode(".png", current)
    assert ref_ok and current_ok

    uploaded = client.post(
        "/upload-ref",
        data={"product_type": "local-ui-route-test"},
        files={"file": ("reference.png", ref_png.tobytes(), "image/png")},
    )
    assert uploaded.status_code == 200

    inspected = client.post(
        "/inspect",
        data={"product_type": "local-ui-route-test", "threshold": "0.1"},
        files={"file": ("current.png", current_png.tobytes(), "image/png")},
    )
    assert inspected.status_code == 200
    payload = inspected.json()
    assert payload["status"] == "БРАК"
    assert payload["inspection_id"]
    assert payload["aligned_image_b64"]
    assert payload["diff_map_b64"]
    assert payload["heatmap_b64"]
    assert payload["segmentation_mask_b64"]

    review = client.get(f"/learning/reviews/{payload['inspection_id']}")
    assert review.status_code == 200
    assert review.json()["defects"]


def test_local_inspection_roi_limits_analysis_to_selected_polygon() -> None:
    width, height = 120, 80
    reference = np.full((height, width, 3), 80, dtype=np.uint8)
    outside = reference.copy()
    outside[20:50, 82:108] = 220
    inside = reference.copy()
    inside[20:50, 12:38] = 220

    def encoded(image: np.ndarray) -> bytes:
        ok, payload = cv2.imencode(".png", image)
        assert ok
        return payload.tobytes()

    product_type = "local-ui-roi-test"
    uploaded = client.post(
        "/upload-ref",
        data={"product_type": product_type},
        files={"file": ("reference.png", encoded(reference), "image/png")},
    )
    assert uploaded.status_code == 200
    roi = client.post(
        "/roi-polygon",
        json={
            "product_type": product_type,
            "points": [
                {"x": 0.0, "y": 0.0},
                {"x": 0.5, "y": 0.0},
                {"x": 0.5, "y": 1.0},
                {"x": 0.0, "y": 1.0},
            ],
        },
    )
    assert roi.status_code == 200

    outside_result = client.post(
        "/inspect",
        data={"product_type": product_type, "threshold": "0.1"},
        files={"file": ("outside.png", encoded(outside), "image/png")},
    )
    inside_result = client.post(
        "/inspect",
        data={"product_type": product_type, "threshold": "0.1"},
        files={"file": ("inside.png", encoded(inside), "image/png")},
    )

    assert outside_result.status_code == 200
    assert outside_result.json()["status"] == "ГОДЕН"
    assert inside_result.status_code == 200
    assert inside_result.json()["status"] == "БРАК"


def test_learning_review_list_is_backward_compatible_empty_or_list() -> None:
    response = client.get("/learning/reviews")
    assert response.status_code == 200
    assert isinstance(response.json()["reviews"], list)


def test_accepted_case_image_and_delete_routes(monkeypatch) -> None:
    monkeypatch.setattr(
        inspection_service,
        "get_accepted_normal_case_image",
        lambda case_id: (b"preview", "image/png") if case_id == "case-1" else None,
    )
    monkeypatch.setattr(
        inspection_service,
        "delete_accepted_normal_case",
        lambda case_id: case_id == "case-1",
    )

    image = client.get("/learning/accepted-cases/case-1/image")
    assert image.status_code == 200
    assert image.content == b"preview"
    assert image.headers["content-type"] == "image/png"

    deleted = client.delete("/learning/accepted-cases/case-1")
    assert deleted.status_code == 200
    assert deleted.json()["deleted"] is True

    missing = client.delete("/learning/accepted-cases/missing")
    assert missing.status_code == 404
