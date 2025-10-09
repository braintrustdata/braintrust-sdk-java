import argparse, json, yaml, pathlib
from jinja2 import Environment, FileSystemLoader, StrictUndefined

def load_spec(path):
    raw = yaml.safe_load(pathlib.Path(path).read_text())
    # normalize for templates
    return {"spec": raw, "source_path": str(path)}

def java_const(name):
    """Convert a name to Java constant format (UPPER_CASE)"""
    return name.upper().replace('-', '_').replace('.', '_')

def render(env, tpl, ctx, out):
    text = env.get_template(tpl).render(**ctx)
    outp = pathlib.Path(out)
    outp.parent.mkdir(parents=True, exist_ok=True)
    outp.write_text(text)

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--spec", default="sdk-spec.yaml")
    args = p.parse_args()

    env = Environment(
        loader=FileSystemLoader("."),
        trim_blocks=True, lstrip_blocks=True,
        undefined=StrictUndefined,
    )
    env.globals['java_const'] = java_const
    ctx = load_spec(args.spec) | {
        "java_package": "dev.braintrust.sdk"
    }

    render(env, "./SdkSpec.java.j2", ctx, "out/java/SdkSpec.java")
