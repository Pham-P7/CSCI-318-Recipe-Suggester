function Recipe({name = "error name not given", ...props}){
  const recipeDetails = props;
  return(
    <div className="Recipe">
      <h1>{name}</h1>
    </div>
  );
}
export default Recipe;