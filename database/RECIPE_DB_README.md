## RECIPE DB
`recipeDB.py` was used to create database `db.db` using data from https://www.kaggle.com/datasets/irkaal/foodcom-recipes-and-reviews?utm_source=chatgpt.com

## Imported Pacages
- csv --> to read `.csv` file
- sqlite3 --> to create and write an SQLite database

## Setup
- `.csv` file is read and converted to a list object `rows`
- creates SQlite3 `db.db` and a cursor object `curr`
- table schema with relevant rows is stored in `create` and executed into the database
- relevant column indices are stored in list `col` and SQL statement in `colText`

## Inputting Data
- `max` determines max amount of rows to insert into the database
- iterate through `rows` and format a valid SQL 'INSERT' commmand into `command`
    - relevant columns are filtered using `col`
    - string is also formatted to remove special characters that would impact SQLite execution

## Diet Labeling
- Adds additional columns to `db.db` such as keto and vegan
- Relevant calculations are made to create a filter
- `db.db` is updated via SQL statements
