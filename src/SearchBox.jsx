import "./SearchBox.css";
import Search from "./assets/search.svg?react";

export default function SearchBox(){
    return (
        <div className="SearchBox">
            <SearchBar />
        </div>
    );
}

function SearchBar(){
    return(
        <div className="SearchBar">
            <input type="text" name="SearchBar-input" placeholder="Find..." className="SearchBar" />
            <SearchIcon />
        </div>
    );
}

function SearchIcon(){
    return (
        <button className="SearchIcon-button">
            <Search className="SearchIcon"/>
        </button>
    );
}