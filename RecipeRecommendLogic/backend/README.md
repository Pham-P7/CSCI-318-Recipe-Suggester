# Recipe Backend

Java 21 and Spring Boot REST API for Fis4Food, using Cloudflare D1.

## Features

- Search by recipe name, category, calorie range, and ingredients.
- Require all selected ingredients or exclude specified ingredients.
- Browse paginated results and view recipe details, images, and instructions.

## Run

Requires Java 21, internet access, and a valid D1 API token. Run from the backend directory:

```sh
export CLOUDFLARE_ACCOUNT_ID="<account-id>"
export CLOUDFLARE_D1_DATABASE_ID="<database-id>"
export CLOUDFLARE_API_TOKEN="<api-token>"
chmod +x mvnw
./mvnw spring-boot:run
```

The target database must contain the existing `recipes` and `recipe_metadata` tables. Startup does not import data or create tables. Maven Wrapper downloads Maven and dependencies on first use.

Configuration names are listed in [.env.example](.env.example). `.env` files are not loaded automatically. Keep tokens out of Git and frontend code.

## API

Base URL: `http://localhost:8080`

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/health` | Application health; does not check D1 |
| GET | `/api/recipes` | Search and paginate recipes |
| GET | `/api/recipes/{recipeId}` | Get recipe details |

Search parameters: `keyword`, `category`, `minCalories`, `maxCalories`, `ingredients`, `excludedIngredients`, `limit`, and `offset`.

Repeat ingredient parameters for multiple values. `limit` defaults to 50 (range 1–100); `offset` defaults to 0. Results are ordered by recipe ID and include `items`, `limit`, `offset`, and `hasMore`.

```sh
curl 'http://localhost:8080/api/recipes?minCalories=200&maxCalories=500&ingredients=rice&ingredients=milk&limit=10'
curl 'http://localhost:8080/api/recipes/2'
```

Unknown values remain `null`. Ingredient quantities remain unverified (`quantity: null`, `pairStatus: "unresolved"`). Errors return a JSON `message` with status 400, 404, 500, 502, or 504.

See [API documentation](docs/API.md) for parameter rules and response examples.

## Test and Build

```sh
sh scripts/run-checks.sh
./mvnw package
```

The test script runs assertion-based tests with a local D1 simulation; no real D1 token is required. `./mvnw test` alone does not execute these test entry points.

Build output: `target/recipe-backend-0.1.0-SNAPSHOT.jar`.

Commands above target macOS/Linux; Windows builds can use `mvnw.cmd`.

## Frontend Access

CORS allows `http://localhost:5173` and `http://127.0.0.1:5173` by default. Override with `RECIPE_CORS_ALLOWED_ORIGINS`, using comma-separated origins without paths or trailing slashes. The frontend calls this backend, which queries D1 using the server-side token.
