import asyncio

from httpx import ASGITransport, AsyncClient

from app.main import app


async def request(method: str, path: str):
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        return await client.request(method, path)


def test_health() -> None:
    response = asyncio.run(request("GET", "/health"))

    assert response.status_code == 200
    assert response.json() == {"status": "UP", "service": "worker"}
