import "./SearchBox.css";
import {Grid} from '@mui/material';
import Search from "./assets/search.svg?react";

export default function SearchBox(){
    return (
        <Grid container spacing={1} className="SearchBox">
            <Grid size={12}>
                <SearchBar />
            </Grid> 
        </Grid>
    );
}

function SearchBar(){
    return(
        <Grid container spacing={0} className="SearchBar">
            <Grid size={11}>
                <input type="text" name="SearchBar-input" placeholder="Find..." className="SearchBar" /> 
            </Grid>
            <Grid size={1}>
                <SearchIcon />
            </Grid>
        </Grid>
    );
}

function SearchIcon(){
    return (
        <button className="SearchIcon-button">
            <Search className="SearchIcon"/>
        </button>
    );
}