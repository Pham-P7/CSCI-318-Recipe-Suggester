import sqlite3
import csv

with open('recipes.csv', 'r', encoding = 'utf-8') as csvFile:
    csvReader = csv.reader(csvFile)
    rows = list(csvReader)

conn = sqlite3.connect('db.db')
cur = conn.cursor()

# Columns
# RecipeId,Name,AuthorId,AuthorName,CookTime,PrepTime,TotalTime,DatePublished,Description,Images,RecipeCategory,Keywords,RecipeIngredientQuantities,RecipeIngredientParts,AggregatedRating,ReviewCount,Calories,FatContent,SaturatedFatContent,CholesterolContent,SodiumContent,CarbohydrateContent,FiberContent,SugarContent,ProteinContent,RecipeServings,RecipeYield,RecipeInstructions


# SCHEMA CREATION
create = '''CREATE TABLE IF NOT EXISTS recipes(
    recipeId INTEGER PRIMARY KEY,
    name TEXT,
    authorName TEXT,
    cookTime TEXT,
    prepTime TEXT,
    totalTime TEXT,
    description TEXT,
    images TEXT,
    recipeCategory TEXT,
    keywords TEXT,
    recipeIngredientQuantities TEXT,
    recipeIngredientParts TEXT,
    calories DOUBLE,
    fat DOUBLE,
    saturatedFat DOUBLE,
    cholesterol DOUBLE,
    sodium DOUBLE,
    carbohydrate DOUBLE,
    fiber DOUBLE,
    sugar DOUBLE,
    protein DOUBLE,
    recipeServing INTEGER,
    recipeInstructions TEXT
)'''
cur.execute(create)

# RELEVANT COLS
col = [0, 1, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 27]

max = 75001
for i in rows[1:max]:
    command = 'INSERT INTO recipes VALUES('
    for j in col:
        val = i[j]
        if isinstance(val, str):
            if j != 7: val = val.replace('\'', '\'\'') 
            val = '\'' + val + '\''
        command += val + ','
    command = command[:-1] + ')'
    cur.execute(command)
    print(str(i[0]) + ' good')
conn.commit()

#Test Call
cur.execute("SELECT recipeid FROM recipes WHERE recipeid <= 100")
print(cur.fetchall())





