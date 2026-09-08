import { createRouter, createWebHistory } from "vue-router";

const RoutePlaceholder = { template: "<span />" };

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", redirect: "/chat" },
    { path: "/login", name: "login", component: RoutePlaceholder },
    { path: "/chat", name: "chat", component: RoutePlaceholder },
    { path: "/admin", name: "admin", component: RoutePlaceholder },
    { path: "/:pathMatch(.*)*", redirect: "/chat" }
  ]
});
