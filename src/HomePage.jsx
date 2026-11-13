import {Grid} from '@mui/material';
import LogoText from './assets/f4food_pacifico.svg?react';
import SearchBox from "./SearchBox.jsx";
import "./HomePage.css";

export default function HomePage(){
    return (
        <Grid container spacing={2} className="HomePage">
            <Grid size={8}>
                <Header />
            </Grid>
            <Grid size={8}>
                <SearchBox />
            </Grid>
        </Grid>
    );
}

function Header({name = "user", ...props}){
  return (
    <header>
      <LogoText className ="logo"/>
      <h2>Welcome to f4Food &#40;f for food&#41;!</h2>
      <p>What are you hungry for?</p>
    </header>
  );
}