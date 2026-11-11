import { createRouter, createWebHistory } from "vue-router";
import { useAuthStore } from "@/stores/auth";

declare module "vue-router" {
  interface RouteMeta {
    public?: boolean;
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: "/login",
      name: "login",
      component: () => import("@/views/LoginView.vue"),
      meta: { public: true }
    },
    {
      path: "/",
      component: () => import("@/layouts/DashboardLayout.vue"),
      children: [
        { path: "", name: "overview", component: () => import("@/views/OverviewView.vue") },
        { path: "users", name: "users", component: () => import("@/views/UsersView.vue") },
        { path: "schemas", name: "schemas", component: () => import("@/views/SchemasView.vue") },
        {
          path: "frameworks",
          name: "frameworks",
          component: () => import("@/views/FrameworksView.vue")
        },
        { path: "posted", name: "posted", component: () => import("@/views/PostedView.vue") },
        { path: "query", name: "query", component: () => import("@/views/QueryView.vue") }
      ]
    }
  ]
});

router.beforeEach(async (to) => {
  const auth = useAuthStore();
  const code = typeof to.query.code === "string" ? to.query.code : "";
  const state = typeof to.query.state === "string" ? to.query.state : "";

  if (code) {
    try {
      await auth.completeLogin(code, state);
      return { path: "/" };
    } catch (error) {
      return {
        name: "login",
        query: { error: error instanceof Error ? error.message : "Login failed" }
      };
    }
  }

  if (to.meta.public) {
    if (to.name === "login" && auth.isAuthenticated && (await auth.ensureFresh())) {
      return { path: "/" };
    }
    return true;
  }

  if (!auth.isAuthenticated || !(await auth.ensureFresh())) {
    return { name: "login" };
  }
  return true;
});

export default router;
