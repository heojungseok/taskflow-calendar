import http from 'k6/http';
import { sleep } from 'k6';

const base = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  vus: Number(__ENV.VUS || 20),
  duration: __ENV.DURATION || '3m',
};

function signIn(jar) {
  http.post(`${base}/api/auth/demo`);
  const xsrf = jar.cookiesForURL(base)['XSRF-TOKEN'][0];
  if (__ENV.SESSION) {
    jar.set(base, 'TASKFLOW_SESSION', __ENV.SESSION);
  } else {
    http.post(`${base}/api/auth/demo`, null, { headers: { 'X-XSRF-TOKEN': xsrf } });
  }
  return xsrf;
}

export function setup() {
  const jar = http.cookieJar();
  const xsrf = signIn(jar);
  const cookies = Object.entries(jar.cookiesForURL(base)).map(([name, values]) => `${name}=${values[0]}`);
  const header = { headers: { Cookie: cookies.join('; '), 'X-XSRF-TOKEN': xsrf } };
  const json = { headers: { ...header.headers, 'Content-Type': 'application/json' } };
  const project = http.post(`${base}/api/projects`, JSON.stringify({ name: 'eval' }), json).json('data');
  const tasks = [
    { title: '배포 체크리스트 정리', dueAt: '2026-12-01T18:00:00' },
    { title: '주간 회고 준비', dueAt: '2026-11-28T10:00:00' },
    { title: '아이디어 메모' },
  ];
  for (const task of tasks) {
    http.post(`${base}/api/projects/${project.id}/tasks`, JSON.stringify(task), json);
  }
  return { header, projectId: project.id };
}

export default function ({ header, projectId }) {
  if (__ENV.MODE === 'recommend') {
    http.get(`${base}/api/projects/${projectId}/task-recommendations`, header);
  } else {
    http.get(`${base}/api/projects`, header);
    http.get(`${base}/api/projects/${projectId}/tasks`, header);
  }
  sleep(Number(__ENV.PAUSE || 1));
}
