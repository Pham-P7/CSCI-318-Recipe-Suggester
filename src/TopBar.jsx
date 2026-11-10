import './TopBar.css';
import Logo from './assets/f4food_logo.svg?react'; //currently unused logo icon only
import LogoText from './assets/f4food_pacifico.svg?react';
import AccountIcon from './assets/account.svg?react';

function TopLogo() {
  return (
    <a href='/' className="top-bar-logo-link">
        <LogoText className="top-bar-logo" />    
    </a>
  );
}

function Account(){
  return(
    <a href='/' className="top-bar-account-button">
      <AccountIcon className="top-bar-account"/>
    </a>
  );
}

export default function TopBar() {
  return (
    <div className="top-bar">
        <TopLogo />
        <Account />
    </div>
  );
}