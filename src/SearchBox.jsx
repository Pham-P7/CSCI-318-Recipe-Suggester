import "./SearchBox.css";
import {Grid} from '@mui/material';
import SearchIcon from "./assets/search.svg?react";

export default function SearchBox(){
    return (
        <Grid container spacing={2}>
            <Grid size={8} className="SearchBox">
                <SearchBar />
            </Grid>
        </Grid>
    );
}

function SearchBar(){
    return(
        <Grid container spacing={1} className="SearchBar">
            <Grid size={12}>
                <input type="text" name="SearchBar-input" placeholder="Find..." className="SearchBar" />   
            </Grid>
            <Grid size ={2}>
                <SearchIcon />
            </Grid>
        </Grid>
    );
}

function SearchIcon(){
    return (
        <button className="SearchIcon-button">
            <SearchIcon/>
        </button>
    );
}