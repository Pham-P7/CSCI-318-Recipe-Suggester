import Cloudflare from 'cloudflare';

let api = "API TOKEN"
let a_id = "ACCOUNT ID"
let d_id = "USER_INFO DATABASE ID"

let logged = false;

const client = new Cloudflare({
  apiToken: api,
});

const query = async (sql) => {
    for await (const queryResult of client.d1.database.query(d_id, {
            account_id: a_id,
            sql: sql
        })) {
        return queryResult;
    }
}

const signUp = async(user, pass) => {
    if (user.length == 0 || !(user && !user.trim())) {
        console.log('Invalid Username');
        return false;
    }
    let q = await query(`SELECT COUNT(username) AS count FROM accounts WHERE username == \'${user}\'`);
    if (q.results[0]['count'] > 0) {
        console.log('Username is already taken');
        return false;
    }
    console.log('Username is valid');
    let account = `INSERT INTO accounts(username, password) VALUES(\'${user}\',\'${pass}}\')`;
    q = await query(account);
    console.log(q.meta.changed_db);
    return true;
}

const login = async(user, pass) => {
    let q = await query(`SELECT COUNT(username) AS count FROM accounts WHERE username == \'${user}\'`);
    if (q.results[0]['count'] == 0) {
        console.log('Username does not exist');
        return null;
    }
    let retrieve = `SELECT * FROM accounts WHERE username == \'${user}\'`;
    q = await query(retrieve);
    if (q.results[0]['password'] !== pass) {
        console.log('Wrong password');
        return null;
    }
    logged = true;
    let list =  q.results[0]['list'];
    console.log(list)
    return list.split(',');
}

//bookmarked is an array of "recipeid"
const updateBookmarks = async(user, bookmarked) => {
    if (!logged) {
        console.log('Not logged in');
        return false;
    }
    let list = bookmarked.join(',');
    let add = `UPDATE accounts
        SET list = \'${list}\'
        WHERE username == \'${user}\'`;
    let q = await query(add);
    console.log(q.meta.changed_db)
    return q.meta.changed_db;
}

function logout() {
    logged = false;
}