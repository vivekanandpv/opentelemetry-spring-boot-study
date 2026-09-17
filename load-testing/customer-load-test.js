// Generates a mixed, weighted stream of GET/POST/PUT/DELETE traffic against the
// Customer API, deliberately including 200/201/204/400/404/409 responses, so
// Tempo/Prometheus/Loki have rich, varied telemetry to explore in Grafana.
import http from "k6/http";
import { check, sleep } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const JSON_HEADERS = { headers: { "Content-Type": "application/json" } };

export const options = {
  vus: Number(__ENV.VUS || 20),
  duration: __ENV.DURATION || "2m",
};

// per-VU memory of customers this VU has created, so GET/PUT/DELETE have real targets to hit
let createdIds = [];

function randomString(length) {
  const chars = "abcdefghijklmnopqrstuvwxyz";
  let s = "";
  for (let i = 0; i < length; i++) s += chars[Math.floor(Math.random() * chars.length)];
  return s;
}

function randomCustomerPayload() {
  const first = randomString(6);
  const last = randomString(8);
  return {
    firstName: first,
    lastName: last,
    email: `${first}.${last}.${__VU}.${__ITER}.${Date.now()}@example.com`, // unique per request, avoids accidental 409s
    phone: `+1 555-${String(Math.floor(Math.random() * 10000)).padStart(4, "0")}`,
  };
}

function listCustomers() {
  const res = http.get(`${BASE_URL}/api/v1/customers`);
  check(res, { "list: 200": (r) => r.status === 200 });
}

function getCustomerHit() {
  if (createdIds.length === 0) return getCustomerMiss();
  const id = createdIds[Math.floor(Math.random() * createdIds.length)];
  const res = http.get(`${BASE_URL}/api/v1/customers/${id}`);
  check(res, { "get by id: 200 or 404": (r) => r.status === 200 || r.status === 404 });
}

function getCustomerMiss() {
  const id = 900000 + Math.floor(Math.random() * 100000); // well outside any real id range -> guaranteed 404
  const res = http.get(`${BASE_URL}/api/v1/customers/${id}`);
  check(res, { "get by id (miss): 404": (r) => r.status === 404 });
}

function createCustomerValid() {
  const res = http.post(`${BASE_URL}/api/v1/customers`, JSON.stringify(randomCustomerPayload()), JSON_HEADERS);
  check(res, { "create: 201": (r) => r.status === 201 });
  if (res.status === 201) {
    createdIds.push(JSON.parse(res.body).id);
    if (createdIds.length > 200) createdIds.shift(); // cap per-VU memory
  }
}

function createCustomerInvalid() {
  const res = http.post(
    `${BASE_URL}/api/v1/customers`,
    JSON.stringify({ firstName: "", lastName: "", email: "not-an-email", phone: "bad-phone!!" }),
    JSON_HEADERS
  );
  check(res, { "create invalid: 400": (r) => r.status === 400 });
}

function createCustomerDuplicate() {
  if (createdIds.length === 0) return createCustomerValid();
  const id = createdIds[createdIds.length - 1];
  const existing = http.get(`${BASE_URL}/api/v1/customers/${id}`);
  if (existing.status !== 200) return;
  const res = http.post(
    `${BASE_URL}/api/v1/customers`,
    JSON.stringify({ firstName: "Dup", lastName: "Customer", email: JSON.parse(existing.body).email, phone: "+1 555-0000" }),
    JSON_HEADERS
  );
  check(res, { "create duplicate: 409": (r) => r.status === 409 });
}

function updateCustomerHit() {
  if (createdIds.length === 0) return;
  const id = createdIds[Math.floor(Math.random() * createdIds.length)];
  const res = http.put(`${BASE_URL}/api/v1/customers/${id}`, JSON.stringify(randomCustomerPayload()), JSON_HEADERS);
  check(res, { "update: 200 or 404": (r) => r.status === 200 || r.status === 404 });
}

function updateCustomerMiss() {
  const id = 900000 + Math.floor(Math.random() * 100000);
  const res = http.put(`${BASE_URL}/api/v1/customers/${id}`, JSON.stringify(randomCustomerPayload()), JSON_HEADERS);
  check(res, { "update miss: 404": (r) => r.status === 404 });
}

function deleteCustomer() {
  if (createdIds.length === 0) return;
  const idx = Math.floor(Math.random() * createdIds.length);
  const res = http.del(`${BASE_URL}/api/v1/customers/${createdIds[idx]}`);
  check(res, { "delete: 204 or 404": (r) => r.status === 204 || r.status === 404 });
  if (res.status === 204) createdIds.splice(idx, 1);
}

// weighted mix, skewed toward reads like typical real traffic
const actions = [
  { weight: 30, fn: listCustomers },
  { weight: 20, fn: getCustomerHit },
  { weight: 10, fn: getCustomerMiss },
  { weight: 15, fn: createCustomerValid },
  { weight: 5, fn: createCustomerInvalid },
  { weight: 3, fn: createCustomerDuplicate },
  { weight: 10, fn: updateCustomerHit },
  { weight: 2, fn: updateCustomerMiss },
  { weight: 5, fn: deleteCustomer },
];
const totalWeight = actions.reduce((sum, a) => sum + a.weight, 0);

function pickAction() {
  let r = Math.random() * totalWeight;
  for (const a of actions) {
    if (r < a.weight) return a.fn;
    r -= a.weight;
  }
  return actions[0].fn;
}

export default function () {
  pickAction()();
  sleep(Math.random() * 0.5); // jitter so requests aren't perfectly uniform
}
