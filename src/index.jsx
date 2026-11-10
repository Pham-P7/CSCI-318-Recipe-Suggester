import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.jsx' 

createRoot(document.getElementById('root')).render( //remove StrictMode for live production version
    <App />
)


// const entryPoint = document.getElementById("root");
// ReactDOM.createRoot(entryPoint).render(<App />);
