from fastapi.testclient import TestClient

from app.main import app


client = TestClient(app)


def test_learning_review_page_is_available() -> None:
    response = client.get("/learning-review")
    assert response.status_code == 200
    assert "Обучение допустимым фрагментам" in response.text


def test_learning_review_list_is_backward_compatible_empty_or_list() -> None:
    response = client.get("/learning/reviews")
    assert response.status_code == 200
    assert isinstance(response.json()["reviews"], list)
