import http from 'k6/http';
import { sleep } from 'k6';

const base = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  vus: Number(__ENV.VUS || 20),
  duration: __ENV.DURATION || '3m',
};

function signIn() {
  const jar = http.cookieJar();
  http.post(`${base}/api/auth/demo`);
  const xsrf = (jar.cookiesForURL(base)['XSRF-TOKEN'] || [])[0];
  if (!xsrf) throw new Error('XSRF-TOKEN cookie missing');
  if (__ENV.SESSION) {
    jar.set(base, 'TASKFLOW_SESSION', __ENV.SESSION);
  } else {
    http.post(`${base}/api/auth/demo`, null, { headers: { 'X-XSRF-TOKEN': xsrf } });
  }
  const cookies = Object.entries(jar.cookiesForURL(base)).map(([name, values]) => `${name}=${values[0]}`);
  const header = { headers: { Cookie: cookies.join('; '), 'X-XSRF-TOKEN': xsrf } };
  const json = { headers: { ...header.headers, 'Content-Type': 'application/json' } };
  const created = http.post(`${base}/api/projects`, JSON.stringify({ name: 'eval' }), json);
  if (created.status !== 200 && created.status !== 201) throw new Error(`project create failed: ${created.status}`);
  const projectId = created.json('data').id;
  const tasks = [
    { title: '배포 체크리스트 정리', dueAt: '2026-12-01T18:00:00' },
    { title: '주간 회고 준비', dueAt: '2026-11-28T10:00:00' },
    { title: '아이디어 메모' },
  ];
  for (const task of tasks) {
    http.post(`${base}/api/projects/${projectId}/tasks`, JSON.stringify(task), json);
  }
  return { header, projectId };
}

export function setup() {
  return __ENV.MODE === 'visitors' ? {} : signIn();
}

let visitor;

export default function (shared) {
  if (__ENV.MODE === 'visitors' && !visitor) visitor = signIn();
  const { header, projectId } = visitor || shared;
  if (__ENV.MODE === 'recommend') {
    http.get(`${base}/api/projects/${projectId}/task-recommendations`, header);
  } else {
    http.get(`${base}/api/projects`, header);
    http.get(`${base}/api/projects/${projectId}/tasks`, header);
  }
  sleep(Number(__ENV.PAUSE || 1));
}
