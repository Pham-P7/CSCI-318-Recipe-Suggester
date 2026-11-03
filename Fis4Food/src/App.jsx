function Header(name = "user", ...props){
  return (
    <header>
      <h1>Fis4Food</h1>
      <p>
        welcome {name} to Fis4Food &#40;F is for food&#41;!
      </p>
    </header>
  );
}
function App() {
  return (
    <div className="App">
      <Header></Header>
    </div>
  );
}

export default App;