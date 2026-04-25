from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Environment-driven configuration for the ETL worker.

    Keys are read from env vars prefixed with ``PLRS_ETL_`` (e.g.
    ``PLRS_ETL_BOOTSTRAP_SERVERS``).
    """

    model_config = SettingsConfigDict(env_prefix="PLRS_ETL_")

    bootstrap_servers: str = "localhost:9092"
    topic: str = "plrs.interactions"
    group_id: str = "plrs-etl"
    dw_db_url: str = "postgresql://plrs:plrs@localhost:5432/plrs"


settings = Settings()
