from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Environment-driven configuration for the PLRS ML service.

    All keys are read from environment variables prefixed with
    ``PLRS_ML_`` (e.g. ``PLRS_ML_OPS_DB_URL``).
    """

    model_config = SettingsConfigDict(env_prefix="PLRS_ML_")

    ops_db_url: str = "postgresql://plrs:plrs@localhost:5432/plrs"
    redis_url: str = "redis://localhost:6379/0"
    hmac_secret: str = "dev-secret-replace-in-prod"


settings = Settings()
