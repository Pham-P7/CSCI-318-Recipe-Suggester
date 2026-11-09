import TopBar from "./TopBar.jsx";
import LogoText from './assets/f4food_pacifico.svg?react';
import SearchBox from "./SearchBox.jsx";
import "./App.css";

function Header({name = "user", ...props}){
  return (
    <header>
      <LogoText className ="logo"/>
      <h2>Welcome to f4Food &#40;f for food&#41;!</h2>
      <p>What are you hungry for?</p>
    </header>
  );
}
function App() {  
  return (
    <div className="App">
      <TopBar></TopBar>
      <Header></Header>
        <main>
          <SearchBox />
        </main>
    </div>
  );
}

export default App;