import asyncio

from httpx import ASGITransport, AsyncClient

from app.main import app


async def request(method: str, path: str, **kwargs):
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        return await client.request(method, path, **kwargs)


def test_community_detection_is_deterministic_and_complete() -> None:
    payload = {
        "nodes": ["n3", "n1", "n2", "n4"],
        "edges": [
            {"source": "n1", "target": "n2", "weight": 4},
            {"source": "n3", "target": "n4", "weight": 4},
        ],
        "seed": 42,
        "resolution": 1.0,
    }

    first = asyncio.run(request("POST", "/community-detection", json=payload))
    second = asyncio.run(request("POST", "/community-detection", json=payload))

    assert first.status_code == 200
    assert first.json() == second.json()
    assert first.json()["algorithm"] == "leidenalg"
    assert [item["node"] for item in first.json()["assignments"]] == [
        "n1",
        "n2",
        "n3",
        "n4",
    ]
    assert len(
        {item["community"] for item in first.json()["assignments"]}
    ) == 2


def test_community_detection_rejects_unknown_edge_node() -> None:
    response = asyncio.run(request(
        "POST", "/community-detection",
        json={
            "nodes": ["n1"],
            "edges": [{"source": "n1", "target": "n2"}],
        },
    ))

    assert response.status_code == 422
