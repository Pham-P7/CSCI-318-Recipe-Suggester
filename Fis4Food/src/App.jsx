import Logo from './assets/f4food_logo.svg?react';

function CornerLogo() {
  return <Logo className="w-6 h-6 text-blue-500 hover:text-blue-700" />;
}
function Header({name = "user", ...props}){
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
      <CornerLogo></CornerLogo>
      <Header></Header>
        <main>
          <h2>What are you hungry for?</h2>
        </main>
    </div>
  );
}

export default App;