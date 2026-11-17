#!/bin/sh
set -eu
cd "$(dirname "$0")/.."

./mvnw -q "$@" test-compile dependency:build-classpath -Dmdep.outputFile=target/dependency-classpath.txt
classpath="target/classes:target/test-classes:$(cat target/dependency-classpath.txt)"
count=0
for test_class in \
  com.fis4food.recipe.RecipeCorsTest \
  com.fis4food.recipe.ApiSmokeCheckTest \
  com.fis4food.recipe.ExistingDatabaseTest \
  com.fis4food.recipe.RecipeCalorieTest \
  com.fis4food.recipe.RecipeD1IntegrationTest \
  com.fis4food.recipe.RecipeErrorTest \
  com.fis4food.recipe.RecipeExcludedIngredientsTest \
  com.fis4food.recipe.RecipeIngredientsFilterTest \
  com.fis4food.recipe.RecipePageTest \
  com.fis4food.recipe.RecipeTextSearchTest \
  com.fis4food.recipe.repository.D1ClientTest \
  com.fis4food.recipe.repository.RecipeIngredientsTest \
  com.fis4food.recipe.repository.RecipeRepositoryTest \
  com.fis4food.recipe.service.RecipeServiceTest
do
  java -ea -cp "$classpath" "$test_class"
  count=$((count + 1))
done
printf 'PASS: all %s test entry points.\n' "$count"
