"""
Local override for the libffi recipe.

Why: the upstream p4a recipe runs `autoreconf -vif` on a GitHub source
archive, which fails on CI runners that lack `libltdl-dev`:

    configure.ac:215: error: possibly undefined macro: LT_SYS_SYMBOL_USCORE

Fix: use the official release tarball (which ships a pre-generated
`configure` script) and skip autogen/autoreconf entirely. We also strip
the version-info from the produced library (same effect as upstream's
remove-version-info.patch) by editing Makefile.in before configuring,
so the SONAME stays `libffi.so`.
"""
from os.path import join
from multiprocessing import cpu_count

import sh
from pythonforandroid.recipe import Recipe
from pythonforandroid.logger import shprint
from pythonforandroid.util import current_directory


class LibffiRecipe(Recipe):
    name = 'libffi'
    version = '3.4.4'
    url = 'https://github.com/libffi/libffi/releases/download/v{version}/libffi-{version}.tar.gz'

    built_libraries = {'libffi.so': '.libs'}

    def build_arch(self, arch):
        env = self.get_recipe_env(arch)
        with current_directory(self.get_build_dir(arch.arch)):
            # همان کاری که پچ remove-version-info می‌کرد، روی Makefile.in:
            shprint(sh.sed, '-i',
                    r's/\$(libffi_version_info) \$(libffi_version_script)/-avoid-version/',
                    'Makefile.in')
            shprint(sh.Command('./configure'),
                    '--host=' + arch.command_prefix,
                    '--prefix=' + self.get_build_dir(arch.arch),
                    '--disable-builddir',
                    '--enable-shared', _env=env)
            shprint(sh.make, '-j', str(cpu_count()), 'libffi.la', _env=env)

    def get_include_dirs(self, arch):
        return [join(self.get_build_dir(arch), 'include')]


recipe = LibffiRecipe()
