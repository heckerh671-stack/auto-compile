// Linux preview harness: runs the native GUI with demo modules and dumps vertices.
#define MAKO_HARNESS
#include "../native/zalard_gui.cpp"
#include <cstdio>
#include <cstdlib>
static void dump(const char* path) { FILE* f = fopen(path, "wb"); fwrite(&vcount, 4, 1, f); fwrite(VB, sizeof(Vtx), vcount, f); fclose(f); }
int main(int argc, char** argv) {
    buildModules(); focused = -1; noClip();
    page = atoi(argv[1]);
    float mxp = argc > 3 ? atof(argv[3]) : 700, myp = argc > 4 ? atof(argv[4]) : 600;
    for (int i = 0; i < 60; i++) frame(1920, 1080, mxp, myp, 1 / 60.f);
    dump(argv[2]); printf("%d verts\n", vcount);
}
